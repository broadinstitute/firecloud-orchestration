package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.HttpClient.PerformExternalRequest
import org.broadinstitute.dsde.firecloud.service.FireCloudTransformers
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.slf4j.LoggerFactory
import spray.client.pipelining
import spray.client.pipelining._
import spray.http._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}


object HttpClient {

  case class PerformExternalRequest(request: HttpRequest)

  def props(requestContext: RequestContext): Props = Props(new HttpClient(requestContext))

  def createJsonHttpEntity(json: String) = {
    HttpEntity(ContentType(MediaType.custom("application", "json")), json)
  }

}

class HttpClient (requestContext: RequestContext) extends Actor with FireCloudTransformers {

  import system.dispatcher
  implicit val system = context.system

  lazy val log = LoggerFactory.getLogger(getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {

    case PerformExternalRequest(externalRequest: HttpRequest) =>
      createResponseFutureFromExternalRequest(requestContext, externalRequest)

  }

  def createResponseFutureFromExternalRequest(
      requestContext: RequestContext,
      externalRequest: HttpRequest): Unit = {
    val pipeline: HttpRequest => Future[HttpResponse] =
      authHeaders(requestContext) ~> sendReceive
    log.debug("Sending request: " + externalRequest)
    pipeline(externalRequest) onComplete {
      case Success(response) =>
        log.debug("Got response: " + response)
        context.parent ! RequestCompleteWithHeaders(response, response.headers.filterNot(isAutomaticHeader):_*)
      case Failure(error) =>
        log.error("External request failed", error)
        context.parent ! RequestComplete(StatusCodes.InternalServerError, error.getMessage)
    }
  }

  private def isAutomaticHeader(h: HttpHeader): Boolean = h match {
    case _:HttpHeaders.Date => true
    case _:HttpHeaders.Server => true
    case _:HttpHeaders.`Content-Type` => true
    case _:HttpHeaders.`Content-Length` => true
    case _ => false
  }
}
