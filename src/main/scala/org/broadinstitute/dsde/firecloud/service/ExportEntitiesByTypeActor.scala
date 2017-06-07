package org.broadinstitute.dsde.firecloud.service

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, ActorContext, ActorRef, ActorRefFactory, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import better.files.File
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.StreamEntities
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.rawls.model._
import org.slf4j.{Logger, LoggerFactory}
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http._
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

  /**
    * TODO:
    * Handle failures
    * Handle membership file
    * Determine whether to build one file or two.
    *
    */
  def streamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Stream[Array[Byte]]] = {
    // Get entity metadata: count and full list of attributes
    getEntityTypeMetadata(workspaceNamespace, workspaceName, entityType) flatMap {

      // TODO: Need a collection type check in here so we can fire off requests for an entity file and a membership file if needed.
      case Some(metadata) =>
        // Generate all of the paginated queries to find all of the entities
        val entityQueries = generateEntityQueries(metadata, entityType)
        val entityTsvWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, entityQueries.size))
        val file = bufferEntitiesToFile(entityTsvWriter, entityQueries, workspaceNamespace, workspaceName, entityType)
        // File.bytes is an Iterator[Byte]. Convert to a 1M byte array stream to limit what's in memory
        file.map(_.bytes.grouped(1024 * 1024).map(_.toArray).toStream)

      case _ => Future(Stream[Array[Byte]]("".getBytes))

    }

  }

  private def generateEntityQueries(metadata: EntityTypeMetadata, entityType: String): Seq[EntityQuery] = {
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

  // We batch entities to a temp file to minimize memory use
  private def bufferEntitiesToFile(tsvWriter: ActorRef, entityQueries: Seq[EntityQuery], workspaceNamespace: String, workspaceName: String, entityType: String): Future[File] = {
    implicit val timeout = Timeout(10 minute)
    // batch queries into groups so we don't drop a bunch of hot futures into the stack
    val maxConnections = ConfigFactory.load().getInt("spray.can.host-connector.max-connections")
    val groupedQueries = entityQueries.grouped(maxConnections).toSeq
    // Fold over the groups, collect entities for each group, write entities to file, collect file responses
    val foldOperation = groupedQueries.foldLeft(Future.successful(Seq[File]())) { (accumulator, queryGroup) =>
      for {
        acc <- accumulator
        entityBatch <- Future.sequence(
          queryGroup.map { query =>
            getEntities(workspaceNamespace, workspaceName, entityType, query)
          }
        ).map(_.flatten)
        file <- (tsvWriter ? Write(acc.size, entityBatch)).mapTo[File]
      } yield acc :+ file
    }
    foldOperation map { files => files.last }
  }

  private def getEntityTypeMetadata(workspaceNamespace: String, workspaceName: String, entityType: String): Future[Option[EntityTypeMetadata]] = {
    rawlsDAO.getEntityTypes(workspaceNamespace, workspaceName) map { metadata =>
      metadata.get(entityType)
    }
  }

  private def getEntities(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery): Future[Seq[Entity]] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query) map {
      response => response.results
    }
  }

//  @Deprecated
//  def exportEntities(workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[PerRequestMessage] = {
//    rawlsDAO.fetchAllEntitiesOfType(workspaceNamespace, workspaceName, entityType) map { entities =>
//      ModelSchema.getCollectionMemberType(entityType) match {
//        case Success(Some(collectionType)) =>
//          val collectionMemberType = ModelSchema.getPlural(collectionType)
//          val entityData = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
//          val membershipData = TSVFormatter.makeMembershipTsvString(entities, entityType, collectionType, collectionMemberType.get)
//          val zipBytes: Array[Byte] = getZipBytes(entityType, membershipData, entityData)
//          val zippedFileName = entityType + ".zip"
//          RequestCompleteWithHeaders(
//            HttpEntity(ContentTypes.`application/octet-stream`, zipBytes),
//            HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> zippedFileName)))
//        case _ =>
//          val data = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
//          RequestCompleteWithHeaders(
//            (OK, data),
//            HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)),
//            HttpHeaders.`Content-Type`(`text/plain`))
//      }
//    }
//  }

  private def getZipBytes(entityType: String, membershipData: String, entityData: String): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    zos.putNextEntry(new ZipEntry(entityType + "_membership.tsv"))
    zos.write(membershipData.getBytes)
    zos.closeEntry()
    zos.putNextEntry(new ZipEntry(entityType + "_entity.tsv"))
    zos.write(entityData.getBytes)
    zos.closeEntry()
    zos.finish()
    bos.toByteArray
  }

}
