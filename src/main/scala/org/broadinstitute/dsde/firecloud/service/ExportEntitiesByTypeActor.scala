package org.broadinstitute.dsde.firecloud.service

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, UserInfo}
import org.broadinstitute.dsde.firecloud.service.ExportEntitiesByTypeActor.StreamEntities
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import org.broadinstitute.dsde.rawls.model.{Entity, EntityQuery, EntityQueryResponse, SortDirections}
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http._
import spray.json._
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
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
  override def receive: Receive = {
    case StreamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) => streamEntities(ctx, workspaceNamespace, workspaceName, filename, entityType, attributeNames) pipeTo sender
  }
}

trait ExportEntitiesByType extends FireCloudRequestBuilding {
  val rawlsDAO: RawlsDAO
  implicit val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext

  import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._

  /**
    * Overall approach:
    * Generate a list of all queries to extract all of the entities
    * For each of those, generate individual streams
    * Sum those streams up and pipe it back out to the sender.
    * TODO: Add zip-streaming for set types
    * TODO: Add filename handling
    * TODO: Add attributeName handling
    */
  def streamEntities(ctx: RequestContext, workspaceNamespace: String, workspaceName: String, filename: String, entityType: String, attributeNames: Option[IndexedSeq[String]]): Future[Stream[String]] = {
    val sortField = entityType + "_id"
    val firstQuery: EntityQuery = EntityQuery(page = 1, pageSize = 1, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)

    // Get all of the possible queries
    lazy val pageQueriesFuture: Future[Seq[EntityQuery]] = getQueryResponse(workspaceNamespace, workspaceName, entityType, firstQuery) map {
      queryResponse =>
        val pageSize = 500 // TODO: Should this be a config?
      val pages = queryResponse.resultMetadata.filteredCount/pageSize match {
        case x if x > Math.floor(x) => (Math.floor(x) + 1).toInt
        case x => x.toInt
        case _ => 1
      }
        val range = 1 to pages
        range map {
          page =>
            EntityQuery(page = page, pageSize = pageSize, sortField = sortField, sortDirection = SortDirections.Ascending, filterTerms = None)
        }
    }

    // Make those queries and generate a nested mess of streams
    lazy val nestedEntityFutures: Future[Seq[Future[Seq[Entity]]]] = pageQueriesFuture map { querySeq =>
      querySeq map { q =>
        getEntities(workspaceNamespace, workspaceName, entityType, q)
      }
    }

    // Clean up the nested mess of streams into a single stream and return.
    lazy val seqEntityFuture: Future[Seq[Entity]] = nestedEntityFutures.
      flatMap { f => Future.sequence(f) }.
      map { s => s.flatten }

    // Turn the entities into a stream
    seqEntityFuture map { entities: Seq[Entity] => entities.map { e: Entity => e.toJson.compactPrint }.toStream }
  }

  def getQueryResponse(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery): Future[EntityQueryResponse] = {
    rawlsDAO.queryEntitiesOfType(workspaceNamespace, workspaceName, entityType, query)
  }

  def getEntities(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery): Future[Seq[Entity]] = {
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
