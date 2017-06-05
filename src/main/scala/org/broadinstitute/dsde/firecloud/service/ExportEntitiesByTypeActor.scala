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
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.{ExportEntities, StreamEntities}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.service.TSVWriterActor._
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
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
  case class ExportEntities(workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]) extends ExportEntitiesByTypeMessage
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
    case ExportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames) => exportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
    case StreamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) => streamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
  }
}

trait ExportEntitiesByType extends FireCloudRequestBuilding {
  val rawlsDAO: RawlsDAO
  implicit val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext
  implicit def actorRefFactory: ActorRefFactory

  lazy val log: Logger = LoggerFactory.getLogger(getClass)

  def streamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Stream[Array[Byte]]] = {
    // Get entity metadata: count and full list of attributes
    getEntityTypeMetadata(workspaceNamespace, workspaceName, entityType) map {
      case Some(metadata) =>

        // Generate all of the paginated queries to find all of the entities
        val pageSize = FireCloudConfig.Rawls.defaultPageSize
        val filteredCount = metadata.count
        val sortField = entityType + "_id"
        val pages = filteredCount % pageSize match {
          case x if x == 0 => filteredCount / pageSize
          case x => filteredCount / pageSize + 1
        }
        val pageQueries: Seq[EntityQuery] = (1 to pages) map { page =>
          EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
        }

        // Set up the file-writing actor so we can send it entities as we get them from Rawls
        // We use a temp file so we're not trying to manipulate thousands of entities in memory
        implicit val timeout = Timeout(10 minute)
        val tsvWriter: ActorRef = actorRefFactory.actorOf(TSVWriterActor.props(entityType, metadata.attributeNames, attributeNames, pages))

        // batch queries into groups so we don't drop a bunch of hot futures into the stack
        val maxConnections = ConfigFactory.load().getInt("spray.can.host-connector.max-connections")

        val groupedEntityCalls: Future[Iterator[Seq[Entity]]] = Future.sequence(pageQueries.grouped(maxConnections) flatMap { group =>
          group map { query =>
            getEntities(workspaceNamespace, workspaceName, entityType, query)
          }
        })

        // TODO: Only getting the headers back. Figure out why. Content is definitely being written, maybe a race condition.
        val file: Future[File] = groupedEntityCalls map { group =>
          val indexedGroup = group.toIndexedSeq
          (tsvWriter ? Start()).mapTo[File] andThen {
            case _ => indexedGroup.indices.map { i =>
              (tsvWriter ? Write(i, indexedGroup.apply(i))).mapTo[File]
            }
          } andThen {
            case _ => (tsvWriter ? End()).mapTo[File]
          }
        } flatMap identity

        // File.bytes is an Iterator[Byte]. Convert to a 1M byte array stream to limit what's in memory
        file.map(_.bytes.grouped(1024 * 1024).map(_.toArray).toStream)

      case _ => Future(Stream[Array[Byte]]("".getBytes))

    } flatMap identity

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

  @Deprecated
  def exportEntities(workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[PerRequestMessage] = {
    rawlsDAO.fetchAllEntitiesOfType(workspaceNamespace, workspaceName, entityType) map { entities =>
      ModelSchema.getCollectionMemberType(entityType) match {
        case Success(Some(collectionType)) =>
          val collectionMemberType = ModelSchema.getPlural(collectionType)
          val entityData = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
          val membershipData = TSVFormatter.makeMembershipTsvString(entities, entityType, collectionType, collectionMemberType.get)
          val zipBytes: Array[Byte] = getZipBytes(entityType, membershipData, entityData)
          val zippedFileName = entityType + ".zip"
          RequestCompleteWithHeaders(
            HttpEntity(ContentTypes.`application/octet-stream`, zipBytes),
            HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> zippedFileName)))
        case _ =>
          val data = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
          RequestCompleteWithHeaders(
            (OK, data),
            HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)),
            HttpHeaders.`Content-Type`(`text/plain`))
      }
    }
  }

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
