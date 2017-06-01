package org.broadinstitute.dsde.firecloud.service

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.{ExportEntities, StreamEntities}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import org.broadinstitute.dsde.rawls.model._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http._
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
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
  override def receive: Receive = {
    case ExportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames) => exportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
    case StreamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) => streamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
  }
}

trait ExportEntitiesByType extends FireCloudRequestBuilding {
  val rawlsDAO: RawlsDAO
  implicit val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext

  def streamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Stream[Array[Byte]]] = {
    // Get all of the possible queries
    lazy val pageQueriesFuture: Future[Seq[EntityQuery]] = getEntityTypeMetadata(workspaceNamespace, workspaceName, entityType) map {
      case Some(metadata) =>
        val pageSize = FireCloudConfig.Rawls.defaultPageSize
        val filteredCount = metadata.count
        val sortField = entityType + "_id"
        val pages = filteredCount % pageSize match {
          case x if x == 0 => filteredCount / pageSize
          case x => filteredCount / pageSize + 1
        }
        val range = 1 to pages
        range map { page =>
          EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
        }
      case _ => Seq[EntityQuery]()
    }

    // batch into groups so we don't drop a bunch of hot futures into the stack
    val maxConnections = ConfigFactory.load().getInt("spray.can.host-connector.max-connections")
    val groupedIterator: Future[Iterator[Seq[EntityQuery]]] = pageQueriesFuture map { pageQueries: Seq[EntityQuery] => pageQueries.grouped(maxConnections) }
    val seqEntityFuture: Future[Seq[Entity]] = groupedIterator map { groups =>
      groups.foldLeft(Future.successful(Seq[Entity]())) { (accumulator, group) =>
        for {
          acc <- accumulator
          entityBatch <- Future.sequence(group.map { query =>
            getEntities(workspaceNamespace, workspaceName, entityType, query)
          }).map(_.flatten)
        } yield entityBatch ++ acc
      }
    } flatMap identity

    // Turn the entities into a byte stream
    seqEntityFuture map { entities: Seq[Entity] =>
      getByteStreamFromEntities(entities, entityType, attributeNames)
    }
  }

  private def getByteStreamFromEntities(entities: Seq[Entity], entityType: String, attributeNames: Option[IndexedSeq[String]]): Stream[Array[Byte]] = {
    ModelSchema.getCollectionMemberType(entityType) match {
      case Success(Some(collectionType)) =>
        val collectionMemberType = ModelSchema.getPlural(collectionType)
        val entityData = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
        val membershipData = TSVFormatter.makeMembershipTsvString(entities, entityType, collectionType, collectionMemberType.get)
        val zipBytes: Array[Byte] = getZipBytes(entityType, membershipData, entityData)
        Stream(zipBytes)
      case _ =>
        val entityData = TSVFormatter.makeEntityTsvString(entities, entityType, attributeNames)
        Stream(entityData.getBytes)
    }
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
