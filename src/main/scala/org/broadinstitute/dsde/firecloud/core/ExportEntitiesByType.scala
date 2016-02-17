package org.broadinstitute.dsde.firecloud.core

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.pattern.pipe

import org.broadinstitute.dsde.firecloud.core.ExportEntitiesByType.ProcessEntities
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, ErrorReport}
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter

import spray.client.pipelining._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

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
      val pipeline = authHeaders(requestContext) ~> sendReceive

      // If we have a set type of entity, we need to make multiple calls for each data set.
      val entityUrls = entityType match {
        case x if x.endsWith("_set") => List(baseEntityUrl + "/" + entityType, baseEntityUrl + "/" + x.replace("_set", ""))
        case _ => List(baseEntityUrl + "/" + entityType)
      }

      val urlsFuture: Future[List[HttpResponse]] = Future sequence { entityUrls map { url => pipeline { Get(url) } } }

      urlsFuture map {
        responses =>
          val allSucceeded = responses.forall(_.status == OK)
          allSucceeded match {
            case true =>
              val entities = responses.flatMap(unmarshal[List[EntityWithType]].apply)
              entityType match {
                case x if x.endsWith("_set") =>
                  val rootType = x.replace("_set", "")
                  val zippedFileName = x + ".zip"
                  val rootData = TSVFormatter.makeEntityTsvString(entities, rootType)
                  val membershipData = TSVFormatter.makeMembershipTsvString(entities, entityType)
                  val zipBytes: Array[Byte] = getZipBytes(filename, membershipData, rootType, rootData)
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
              val errors = responses.filterNot(_.status == OK) map { e => (e, ErrorReport.tryUnmarshal(e)) }
              val errorReports = errors collect { case (_, Success(report)) => report }
              val missingReports = errors collect { case (originalError, Failure(_)) => originalError }
              val errorMessage = {
                val baseMessage = "%d failures out of %d attempts retrieving entity urls.  Errors: %s"
                  .format(errors.size, responses.size, errors mkString ",")
                if (missingReports.isEmpty) baseMessage
                else {
                  val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s"
                    .format(missingReports.size, missingReports mkString ",")
                  baseMessage + "\n" + supplementalErrorMessage
                }
              }
              RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports)
          }
      } recover { case e:Throwable =>  RequestCompleteWithErrorReport(InternalServerError, e.getMessage) } pipeTo context.parent
    case _ =>
      Future(RequestComplete(StatusCodes.BadRequest)) pipeTo context.parent
  }

  private def getZipBytes(filename: String, membershipData: String, rootType: String, rootData: String): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    zos.putNextEntry(new ZipEntry(filename))
    zos.write(membershipData.getBytes)
    zos.closeEntry()
    zos.putNextEntry(new ZipEntry(rootType + ".txt"))
    zos.write(rootData.getBytes)
    zos.closeEntry()
    zos.finish()
    bos.toByteArray
  }

}
