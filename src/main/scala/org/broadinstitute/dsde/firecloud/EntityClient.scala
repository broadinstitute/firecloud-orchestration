package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat

import akka.actor.{Actor, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.EntityClient.EntityListRequest
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.http.{HttpRequest, HttpResponse, RequestProcessingException, StatusCodes}
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object EntityClient {

  case class EntityListRequest(workspaceNamespace: String,
                               workspaceName: String,
                               entityType: String)

  def props(requestContext: RequestContext): Props = Props(new EntityClient(requestContext))

}

class EntityClient (requestContext: RequestContext) extends Actor {

  import system.dispatcher

  implicit val system = context.system
  val log = Logging(system, getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case EntityListRequest(workspaceNamespace: String, workspaceName: String, entityType: String) =>
      listEntities(workspaceNamespace, workspaceName, entityType)
  }

  def listEntities(workspaceNamespace: String, workspaceName: String, entityType: String): Unit = {
    log.info("listEntities request received")
    val pipeline: HttpRequest => Future[HttpResponse] =
      addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive
    val responseFuture: Future[HttpResponse] = pipeline {
      Get(s"${FireCloudConfig.Workspace.entityPathFromWorkspace(workspaceNamespace, workspaceName)}/$entityType")
    }

    responseFuture onComplete {
      case Success(response) =>
        response.status match {
          case OK =>
            log.debug("OK response")
            requestContext.complete(response)
          case _ =>
            // Bubble up all other unmarshallable responses
            log.warning("Unanticipated response: " + response.status.defaultMessage)
            requestContext.complete(response)
        }
      case Failure(error) =>
        // Failure accessing service
        log.error(error, "Service API call failed")
        requestContext.failWith(
          new RequestProcessingException(StatusCodes.InternalServerError, error.getMessage))
    }

  }
}

