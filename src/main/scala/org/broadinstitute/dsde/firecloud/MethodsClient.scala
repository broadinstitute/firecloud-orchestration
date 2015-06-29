package org.broadinstitute.dsde.firecloud

import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.MethodsClient.MethodsListRequest
import org.broadinstitute.dsde.firecloud.model.MethodEntity
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import scala.reflect.classTag
import spray.client.pipelining._
import spray.http.HttpHeaders.{Cookie, Origin, RawHeader}
import spray.http.StatusCodes._
import spray.http.{HttpRequest, HttpResponse, RequestProcessingException, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object MethodsClient {
  
  case class MethodsListRequest()

  def props(requestContext: RequestContext): Props = Props(new MethodsClient(requestContext))

}

class MethodsClient(requestContext: RequestContext) extends Actor {

  import DefaultJsonProtocol._
  import system.dispatcher

  implicit val system = context.system
  val log = Logging(system, getClass)

  override def receive: Receive = {
    case MethodsListRequest => listMethods(sender())
  }

  def listMethods(senderRef: ActorRef): Unit = {
    log.info("Find all methods in the methods repository")

    val pipeline: HttpRequest => Future[HttpResponse] =
      addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive

    val responseFuture: Future[HttpResponse] = pipeline { Get(FireCloudConfig.Methods.methodsListUrl) }

    def isWhitelisted(origin: Origin): Boolean = {
      // TODO(dmohs): Check this against a whitelist.
      true
    }

    def addCorsHeaders(requestContext: RequestContext): RequestContext = {
      requestContext.withHttpResponseHeadersMapped(h => {
        val alwaysHeaders = RawHeader("Access-Control-Allow-Credentials", "true") :: RawHeader("Vary", "Origin") :: Nil
        val origin = requestContext.request.header(classTag[Origin])
        if (origin.isDefined && isWhitelisted(origin.get))
          h ++: alwaysHeaders :+ RawHeader("Access-Control-Allow-Origin", origin.get.value)
        else
          h ++: alwaysHeaders
      })
    }

    responseFuture onComplete {
      case Success(response) =>
        response.status match {
          case OK =>
            log.debug("OK response")
            addCorsHeaders(requestContext).complete(
              unmarshal[List[MethodEntity]].apply(response))
            context.stop(self)
          case _ =>
            // Bubble up all other unmarshallable responses
            log.warning("Unanticipated response: " + response.status.defaultMessage)
            addCorsHeaders(requestContext).complete(response)
            context.stop(self)
        }
      case Failure(error) =>
        // Failure accessing service
        log.error(error, "Could not access the methods repository")
        addCorsHeaders(requestContext).failWith(
          new RequestProcessingException(StatusCodes.InternalServerError, error.getMessage))
        context.stop(self)
    }

  }

}
