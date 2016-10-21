package org.broadinstitute.dsde.firecloud.core

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.core.ExportEntitiesByType.{ProcessEntities, ProcessWorkspaceAttributes}
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import spray.client.pipelining._
import spray.http.HttpEncodings._
import spray.http.HttpHeaders.`Accept-Encoding`
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.httpx.encoding.Gzip
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.Success
import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.dataaccess.HttpRawlsDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json.DefaultJsonProtocol._
import spray.http.HttpEntity
import spray.httpx.unmarshalling._
import spray.httpx.SprayJsonSupport._

object ExportEntitiesByType {
  case class ProcessEntities(baseEntityUrl: String, filename: String, entityType: String)
  case class ProcessWorkspaceAttributes(baseWorkspaceUrl: String, workspaceNamespace: String, workspaceName: String, filename: String/*, rawlsDAO: HttpRawlsDAO*/)
  def props(requestContext: RequestContext): Props = Props(new ExportEntitiesByTypeActor(requestContext))
}

class ExportEntitiesByTypeActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding  {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {
    case ProcessEntities(baseEntityUrl: String, filename: String, entityType: String) =>
      processEntities(baseEntityUrl, filename, entityType)
    case ProcessWorkspaceAttributes(baseWorkspaceUrl: String, workspaceNamespace: String, workspaceName: String, filename: String/*, rawlsDAO: HttpRawlsDAO*/) =>
      processWorkspaceAttributes(baseWorkspaceUrl, workspaceNamespace, workspaceName, filename/*, rawlsDAO*/)
    case _ =>
      Future(RequestComplete(StatusCodes.BadRequest)) pipeTo context.parent
  }

  def processWorkspaceAttributes(baseWorkspaceUrl: String, workspaceNamespace: String, workspaceName: String, filename: String/*, rawlsDAO: HttpRawlsDAO*/) = {
    log.info("We're in Process Workspace Attributes!")
    val pipeline = authHeaders(requestContext) ~> addHeader(`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
    pipeline { Get(baseWorkspaceUrl)} map {
      response =>
        log.info("There was some kind of response")
        response.status match {
          case OK =>
            log.info("We got an OK response!")
            log.info("RESPONSE: " + response.toString)
            log.debug("RESPONSE.ENTITY: " + response.entity.toString)
            log.info("RESPONSE.HEADERS: " + response.headers.toString())
            //val entities = unmarshal[RawlsWorkspaceResponse].apply(response)
            val workspace = response.entity.as[RawlsWorkspaceResponse] match {
              case Right(rwr) => rwr
              case Left(error) => throw new FireCloudExceptionWithErrorReport(ErrorReport(response)) // replay the root exception
            }
            /*rawlsDAO.getWorkspace(workspaceNamespace, workspaceName).map{ workspaceResponse =>
              log.info(workspaceResponse.workspace.head.attributes.toString())
            }*/
            log.info("Do we even get here?")
            //log.info("ENTITIES: " + workspace.toString())
          case _ =>
            RequestCompleteWithErrorReport(response.status, response.entity.asString)
        }
    }
  }

  def processEntities(baseEntityUrl: String, filename: String, entityType: String) = {
    val pipeline = authHeaders(requestContext) ~> addHeader(`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
    pipeline { Get(baseEntityUrl + "/" + entityType) } map {
      response =>
        response.status match {
          case OK =>
            log.info("UMMMM RESPONSE: " + response.toString)
            log.info("RESPONSE.ENTITY: " + response.entity.data.toString)
            log.info("RESPONSE.HEADERS: " + response.headers.toString())
            val entities = unmarshal[List[EntityWithType]].apply(response)
            log.info("ENTITIES: " + entities.toString())
            ModelSchema.getCollectionMemberType(entityType) match {
              case Success(collectionType) if collectionType.isDefined =>
                val collectionMemberType = ModelSchema.getPlural(collectionType.get)
                val entityData = TSVFormatter.makeEntityTsvString(entities, entityType)
                val membershipData = TSVFormatter.makeMembershipTsvString(entities, entityType, collectionMemberType.get)
                val zipBytes: Array[Byte] = getZipBytes(entityType, membershipData, entityData)
                val zippedFileName = entityType + ".zip"
                RequestCompleteWithHeaders(
                  HttpEntity(ContentTypes.`application/octet-stream`, zipBytes),
                  HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> zippedFileName)))
              case _ =>
                val data = TSVFormatter.makeEntityTsvString(entities, entityType)
                RequestCompleteWithHeaders(
                  (OK, data),
                  HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)),
                  HttpHeaders.`Content-Type`(`text/plain`))
            }
          case _ =>
            RequestCompleteWithErrorReport(response.status, response.entity.asString)
        }
    } recover { case e:Throwable =>  RequestCompleteWithErrorReport(InternalServerError, e.getMessage) } pipeTo context.parent
  }

  private def getZipBytes(entityType: String, membershipData: String, entityData: String): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    zos.putNextEntry(new ZipEntry(entityType + "_membership.txt"))
    zos.write(membershipData.getBytes)
    zos.closeEntry()
    zos.putNextEntry(new ZipEntry(entityType + "_entity.txt"))
    zos.write(entityData.getBytes)
    zos.closeEntry()
    zos.finish()
    bos.toByteArray
  }

}
