package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat
import org.broadinstitute.dsde.firecloud.model.RequestCompleteWithErrorReport
import spray.httpx.encoding.Gzip
import spray.http.HttpEncodings._

import scala.concurrent.Future
import scala.util.{Failure, Success}

import akka.actor.{Actor, Props}
import org.slf4j.{Logger, LoggerFactory}
import spray.client.pipelining._
import spray.http.HttpHeaders.{`Accept-Encoding`, Cookie}
import spray.http._
import spray.routing.RequestContext
import spray.http.StatusCodes._

import org.broadinstitute.dsde.firecloud.HttpClient.PerformExternalRequest
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete

object HttpClient {

  case class PerformExternalRequest(requestCompression: Boolean, request: HttpRequest)

  lazy val chunkLimitDisplay = FireCloudConfig.Spray.chunkLimit
    .replace("m", "MB")
    .replace("k", "KB")


  def props(requestContext: RequestContext): Props = Props(new HttpClient(requestContext))

  def createJsonHttpEntity(json: String) = {
    HttpEntity(ContentType(MediaType.custom("application", "json")), json)
  }

}

class HttpClient (requestContext: RequestContext) extends Actor
    with FireCloudRequestBuilding with LogRequestBuilding {

  import system.dispatcher
  implicit val system = context.system

  lazy val log = LoggerFactory.getLogger(getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {

    case PerformExternalRequest(requestCompression: Boolean, externalRequest: HttpRequest) =>
      createResponseFutureFromExternalRequest(requestCompression, requestContext, externalRequest)

  }

  def createResponseFutureFromExternalRequest(
      requestCompression: Boolean,
      requestContext: RequestContext,
      externalRequest: HttpRequest): Unit = {
    val pipeline: HttpRequest => Future[HttpResponse] =
      requestCompression match {
        case true =>
          authHeaders(requestContext) ~> addHeaders(`Accept-Encoding`(gzip)) ~> logRequest(log) ~> sendReceive ~> decode(Gzip)
        case _ =>
          authHeaders(requestContext) ~> logRequest(log) ~> sendReceive
      }
    pipeline(externalRequest) onComplete {
      case Success(response) =>
        log.debug("Got response: " + response)
        context.parent ! RequestComplete(response)
      case Failure(re:RuntimeException) if re.getMessage.startsWith("sendReceive doesn't support chunked responses")  =>
        val message = s"The response payload was over ${HttpClient.chunkLimitDisplay} and cannot be processed. " +
                      s"Original request url: ${externalRequest.uri.toString}"
        val customException = new FireCloudException(message, re)
        log.error(message, customException)
        context.parent ! RequestCompleteWithErrorReport(InternalServerError, message, customException)
      case Failure(error) =>
        val message = s"External request failed to ${externalRequest.uri.toString()} - ${error.getMessage}"
        val customException = new FireCloudException(message, error)

        log.error(message, customException)
        context.parent ! RequestCompleteWithErrorReport(InternalServerError, message, customException)
    }
  }
}


trait LogRequestBuilding extends spray.httpx.RequestBuilding {
  def logRequest(log: Logger): RequestTransformer = { request =>
    if (log.isDebugEnabled) {log.debug("Sending request: " + request)}
    request
  }
}
