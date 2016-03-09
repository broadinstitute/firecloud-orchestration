package org.broadinstitute.dsde.firecloud.core

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe

import org.broadinstitute.dsde.firecloud.core.ExportEntitiesByType.ProcessEntities
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{ModelSchema, RequestCompleteWithErrorReport}
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

object ExportEntitiesByType {
  case class ProcessEntities(baseEntityUrl: String, filename: String, entityType: String)
  def props(requestContext: RequestContext): Props = Props(new ExportEntitiesByTypeActor(requestContext))
}

class ExportEntitiesByTypeActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding  {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {
    case ProcessEntities(baseEntityUrl: String, filename: String, entityType: String) =>
      val pipeline = authHeaders(requestContext) ~> addHeader(`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)
      pipeline { Get(baseEntityUrl + "/" + entityType) } map {
        response =>
          response.status match {
            case OK =>
              val entities = unmarshal[List[EntityWithType]].apply(response)
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
    case _ =>
      Future(RequestComplete(StatusCodes.BadRequest)) pipeTo context.parent
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
