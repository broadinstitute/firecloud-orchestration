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
    // Always need an entity writer, sometimes need a membership writer.
    val entityWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
    val membershipWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))

    // Collection Types require two files (entity and membership) zipped together.
    val fileWritingOperation = ModelSchema.isCollectionType(entityType) match {
      case Success(x) if x =>
        // Fold over the groups, collect entities for each group, write entities to file(s), collect file responses
        val foldOperation = groupedQueries.foldLeft(Future.successful(0, Seq[File]())) { (accumulator, queryGroup) =>
          for {
            acc <- accumulator
            entityBatch <- getEntityBatchFromQueries(queryGroup, workspaceNamespace, workspaceName, entityType)
            membershipTSV <- (membershipWriter ? WriteMembershipTSV(acc._1, entityBatch)).mapTo[File]
            entityTSV <- (entityWriter ? WriteEntityTSV(acc._1, entityBatch)).mapTo[File]
            zip <- writeFilesToZip(entityType, membershipTSV, entityTSV)
          } yield (acc._1 + 1, Seq(zip))
        }
        foldOperation map { files => files._2.head }
      case _ =>
        // Fold over the groups, collect entities for each group, write entities to file, collect file results
        val foldOperation = groupedQueries.foldLeft(Future.successful((0, Seq[File]()))) { (accumulator, queryGroup) =>
          for {
            acc <- accumulator
            entityBatch <- getEntityBatchFromQueries(queryGroup, workspaceNamespace, workspaceName, entityType)
            entityTSV <- (entityWriter ? WriteEntityTSV(acc._1, entityBatch)).mapTo[File]
          } yield (acc._1 + 1, Seq(entityTSV))
        }
        foldOperation map { files => files._2.head }
    }
    fileWritingOperation
  }

  private def writeFilesToZip(entityType: String, membershipTSV: File, entityTSV: File): Future[File] = {
    Future {
      log.info("WriteFilesToZip")
      var zipDir = File.newTemporaryDirectory()
      log.info("WriteFilesToZip: Created new zip directory")

      log.info(s"WriteFilesToZip: zipDir exists: ${zipDir.exists}")
      log.info(s"WriteFilesToZip: membershipTSV exists: ${membershipTSV.exists}")
      log.info(s"WriteFilesToZip: entityTSV exists: ${entityTSV.exists}")

      membershipTSV.copyTo(zipDir)
      log.info("WriteFilesToZip: Copied membership to zip directory")
      membershipTSV.renameTo(entityType + "_membership.tsv")
      log.info("WriteFilesToZip: Renamed membership")

      entityTSV.copyTo(zipDir)
      log.info("WriteFilesToZip: Copied entity")
      entityTSV.renameTo(entityType + "_entity.tsv")
      log.info("WriteFilesToZip: Renamed entity")

      zipDir.zip()
    }
  }

  private def getEntityBatchFromQueries(queryGroup: Seq[EntityQuery], workspaceNamespace: String, workspaceName: String, entityType: String): Future[Seq[Entity]] = {
    Future.sequence(
      queryGroup map { query =>
        getEntities(workspaceNamespace, workspaceName, entityType, query)
      }
    ) map(_.flatten)
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
