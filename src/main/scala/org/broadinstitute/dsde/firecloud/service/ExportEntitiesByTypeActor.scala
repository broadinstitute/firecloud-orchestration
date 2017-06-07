package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, ActorContext, ActorRef, ActorRefFactory, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import better.files.File
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.StreamEntities
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.rawls.model._
import org.slf4j.{Logger, LoggerFactory}
import spray.routing.RequestContext

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Success

object ExportEntitiesByTypeActor {
  sealed trait ExportEntitiesByTypeMessage
  case class StreamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]) extends ExportEntitiesByTypeMessage

  def props(exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor, userInfo: UserInfo): Props = {
    Props(exportEntitiesByTypeConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new ExportEntitiesByTypeActor(app.rawlsDAO, userInfo)
}

class ExportEntitiesByTypeActor(val rawlsDAO: RawlsDAO, val userInfo: UserInfo)(implicit protected val executionContext: ExecutionContext) extends Actor with ExportEntitiesByType {
  // StreamEntities requires its own actor context to work with TsvWriterActor
  def actorRefFactory: ActorContext = context
  override def receive: Receive = {
    case StreamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) => streamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
  }
}

trait ExportEntitiesByType extends FireCloudRequestBuilding {
  val rawlsDAO: RawlsDAO
  implicit val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext
  implicit def actorRefFactory: ActorRefFactory
  implicit val timeout = Timeout(10 minute)

  lazy val log: Logger = LoggerFactory.getLogger(getClass)

  /*
    * TODO:
    * Handle failures
    * Streamline the fold operations ???
    */
  def streamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Stream[Array[Byte]]] = {
    // Get entity metadata: count and full list of attributes
    getEntityTypeMetadata(workspaceNamespace, workspaceName, entityType) flatMap { metadata =>
      // Generate all of the paginated queries to find all of the entities
      val entityQueries = getEntityQueries(metadata, entityType)
      val file = bufferEntitiesToFile(metadata, attributeNames, entityQueries, workspaceNamespace, workspaceName, entityType)
      // File.bytes is an Iterator[Byte]. Convert to a 1M byte array stream to limit what's in memory
      file.map(_.bytes.grouped(1024 * 1024).map(_.toArray).toStream)
    }
  }

  // We batch entities to a temp file(s) to minimize memory use
  private def bufferEntitiesToFile(metadata: EntityTypeMetadata, attributeNames: Option[IndexedSeq[String]], entityQueries: Seq[EntityQuery], workspaceNamespace: String, workspaceName: String, entityType: String): Future[File] = {
    // batch queries into groups so we don't drop a bunch of hot futures into the stack
    val maxConnections = ConfigFactory.load().getInt("spray.can.host-connector.max-connections")
    val groupedQueries = entityQueries.grouped(maxConnections).toSeq

    // Collection Types require two files (entity and membership) zipped together.
    val fileWritingOperation = ModelSchema.isCollectionType(entityType) match {
      case Success(x) if x =>
        val membershipWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
        val entityWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
        // Fold over the groups, collect entities for each group, write entities to file(s), collect file responses
        val foldOperation = groupedQueries.foldLeft(Future.successful(Seq[(File, File)]())) { (accumulator, queryGroup) =>
          for {
            acc <- accumulator
            entityBatch <- Future.sequence(
              queryGroup map { query =>
                getEntities(workspaceNamespace, workspaceName, entityType, query)
              }
            ) map(_.flatten)
            file1 <- (membershipWriter ? WriteMembershipTSV(acc.size, entityBatch)).mapTo[File]
            file2 <- (entityWriter ? WriteEntityTSV(acc.size, entityBatch)).mapTo[File]
          } yield acc :+ (file1, file2)
        }
        val filePair = foldOperation map { files => files.last }
        val zipFile = filePair map { pair: (File, File) =>
          var zipDir = File.newTemporaryDirectory()
          pair._1.renameTo(entityType + "_membership.tsv").zipTo(zipDir)
          pair._2.renameTo(entityType + "_entity.tsv").zipTo(zipDir)
          zipDir
        }
        zipFile
      case _ =>
        // Fold over the groups, collect entities for each group, write entities to file(s), collect file responses
        val entityWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
        val foldOperation = groupedQueries.foldLeft(Future.successful(Seq[File]())) { (accumulator, queryGroup) =>
          for {
            acc <- accumulator
            entityBatch <- Future.sequence(
              queryGroup map { query =>
                getEntities(workspaceNamespace, workspaceName, entityType, query)
              }
            ) map(_.flatten)
            file1 <- (entityWriter ? WriteEntityTSV(acc.size, entityBatch)).mapTo[File]
          } yield acc :+ file1
        }
        foldOperation map { files => files.last }
    }
    fileWritingOperation
  }

  private def getEntityQueries(metadata: EntityTypeMetadata, entityType: String): Seq[EntityQuery] = {
    val pageSize = FireCloudConfig.Rawls.defaultPageSize
    val filteredCount = metadata.count
    val sortField = entityType + "_id"
    val pages = filteredCount % pageSize match {
      case x if x == 0 => filteredCount / pageSize
      case x => filteredCount / pageSize + 1
    }
    (1 to pages) map { page =>
      EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
    }
  }

  private def getEntityTypeMetadata(workspaceNamespace: String, workspaceName: String, entityType: String): Future[EntityTypeMetadata] = {
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName) map { metadata =>
      metadata.get(entityType)
    } map {
      case Some(m) => m
      case _ => throw new FireCloudException(s"Unable to collect entity metadata for $workspaceNamespace:$workspaceName:$entityType")
    }
  }

  private def getEntities(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery): Future[Seq[Entity]] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query) map {
      response => response.results
    }
  }

}
