package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorContext
import akka.event.LoggingAdapter
import spray.client.pipelining._
import spray.http.HttpHeaders.{Cookie, Origin, RawHeader}
import spray.http.StatusCodes._
import spray.http.{HttpRequest, HttpResponse, RequestProcessingException, StatusCodes}
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect._
import scala.util.{Failure, Success}

object ServiceUtils {

  case class ExternalRequestParams(
    log: LoggingAdapter,
    context: ActorContext,
    url: String,
    requestContext: RequestContext,
    completeSuccessfully: (RequestContext, HttpResponse) => Unit
  )

  def completeFromExternalRequest(params: ExternalRequestParams)(implicit ec: ExecutionContext):
      Unit = {
    implicit val system = params.context.system
    val pipeline: HttpRequest => Future[HttpResponse] =
      addHeader(Cookie(params.requestContext.request.cookies)) ~> sendReceive
    val responseFuture: Future[HttpResponse] = pipeline { Get(params.url) }

    responseFuture onComplete {
      case Success(response) =>
        response.status match {
          case OK =>
            params.log.debug("OK response")
            params.completeSuccessfully(addCorsHeaders(params.requestContext), response)
          case _ =>
            // Bubble up all other unmarshallable responses
            params.log.warning("Unanticipated response: " + response.status.defaultMessage)
            addCorsHeaders(params.requestContext).complete(response)
        }
      case Failure(error) =>
        // Failure accessing service
        params.log.error(error, "Service API call failed")
        addCorsHeaders(params.requestContext).failWith(
          new RequestProcessingException(StatusCodes.InternalServerError, error.getMessage))
    }
  }

  def addCorsHeaders(requestContext: RequestContext): RequestContext = {
    requestContext.withHttpResponseHeadersMapped(h => {
      val alwaysHeaders = RawHeader("Access-Control-Allow-Credentials", "true") ::
        RawHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS") ::
        RawHeader("Access-Control-Allow-Headers", "Content-Type") ::
        RawHeader("Vary", "Origin") ::
        Nil
      val origin = requestContext.request.header(classTag[Origin])
      if (origin.isDefined && isWhitelisted(origin.get))
        h ++: alwaysHeaders :+ RawHeader("Access-Control-Allow-Origin", origin.get.value)
      else
        h ++: alwaysHeaders
    })
  }

  private def isWhitelisted(origin: Origin): Boolean = {
    // TODO(dmohs): Check this against a whitelist.
    true
  }
}
