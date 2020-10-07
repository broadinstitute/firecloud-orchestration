package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.{HttpHeader, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.TransferEncodings.gzip
import akka.http.scaladsl.model.headers.{RawHeader, `Accept-Encoding`}
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.pattern.PipeToSupport
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.ProcessUrl
import org.broadinstitute.dsde.firecloud.dataaccess.DsdeHttpDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.rawls.model.Entity
//import spray.client.pipelining._
//import spray.http.HttpEncodings._
//import spray.http.HttpHeaders.`Accept-Encoding`
//import spray.http.HttpResponse
//import spray.http.StatusCodes._
//import spray.httpx.SprayJsonSupport._
//import spray.httpx.encoding.Gzip
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue
//import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object GetEntitiesWithType {
  case class ProcessUrl(url: String)

  def constructor(userInfo: UserInfo) = new GetEntitiesWithTypeActor(userInfo)
}

class GetEntitiesWithTypeActor(userInfo: UserInfo) extends Actor with FireCloudRequestBuilding with DsdeHttpDAO {

  implicit val system = context.system
  implicit val materializer: Materializer
  import system.dispatcher
  import spray.json.DefaultJsonProtocol._

  val log = Logging(system, getClass)

  def ProcessUrl(url: String) = processUrl(url)

  def processUrl(url: String) = {
    log.debug("Processing entity type map for url: " + url)

    //      val pipeline = authHeaders(requestContext) ~> addHeader(`Accept-Encoding`(gzip)) ~> sendReceive ~> decode(Gzip)

    val request = Get(url) //.withHeaders(`Accept-Encoding`(gzip)) //TODO: add headers and decoding

    //      val entityTypesFuture: Future[HttpResponse] = pipeline { Get(url) }
    val allEntitiesResponse = executeRequestRaw(null)(request).flatMap { response => //todo pass userInfo thru to this from request
      response.status match {
        case x if x == OK =>
          //            val entityTypes: List[String] = unmarshal[Map[String, JsValue]].apply(response).keys.toList

          Unmarshal(response).to[Map[String, JsValue]].flatMap { entityTypeMap =>
            val entityTypes = entityTypeMap.keys.toList
            val entityUrls: List[String] = entityTypes.map(s => FireCloudDirectiveUtils.encodeUri(s"$url/$s"))
            val entityFutures: List[Future[HttpResponse]] = entityUrls map { entitiesUrl => executeRequestRaw(null)(Get(entitiesUrl)) }
            getEntitiesForTypesResponse(Future sequence entityFutures, entityTypes)
          }
        case x =>
          Future(RequestComplete(response))
      }
    } recover { case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }
    allEntitiesResponse
  }

  def getEntitiesForTypesResponse(future: Future[List[HttpResponse]], entityTypes: List[String]): Future[PerRequestMessage] = {
    import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
    future.map {
      responses =>
        val allSucceeded = responses.forall(_.status == OK)
        allSucceeded match {
          case true =>
            val entityResponses = Future.traverse(responses) { resp => Unmarshal(resp).to[List[Entity]] }
            entityResponses.map { entities => RequestComplete(OK, entities.flatten) }
          case false =>
            val errors = responses.filterNot(_.status == OK) map { e =>
              (
                e,
                ErrorReport(statusCode = e.status, "test")
              )
            }

            errors.map { case (response, errorReport) =>

            }

            val errorReports = errors collect { case (_, Success(report)) => report }
            val missingReports = errors collect { case (originalError, Failure(_)) => originalError }
            val errorMessage = {
              val baseMessage = "%d failures out of %d attempts retrieving entityUrls.  Errors: %s"
                .format(errors.size, entityTypes.size, errors mkString ",")
              if (missingReports.isEmpty) baseMessage
              else {
                val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s"
                  .format(missingReports.size, missingReports mkString ",")
                baseMessage + "\n" + supplementalErrorMessage
              }
            }
            RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports)
        }
    } recover { case e:Throwable =>  RequestCompleteWithErrorReport(InternalServerError, e.getMessage) }
  }

}
