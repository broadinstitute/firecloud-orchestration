package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.WorkspaceClient.WorkspaceCreate
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{WorkspaceEntity, WorkspaceIngest}
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.http.{HttpRequest, HttpResponse, RequestProcessingException, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}


object WorkspaceClient {

  case class WorkspaceCreate(workspaceIngest: WorkspaceIngest, username: Option[String])

  def props(requestContext: RequestContext): Props = Props(new WorkspaceClient(requestContext))

}

class WorkspaceClient (requestContext: RequestContext) extends Actor {

  import system.dispatcher

  implicit val system = context.system
  val log = Logging(system, getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {

    case WorkspaceCreate(workspaceIngest: WorkspaceIngest, username: Option[String]) =>
      createWorkspace(workspaceIngest, username)

  }

  def createWorkspace(workspaceIngest: WorkspaceIngest, username: Option[String]): Unit = {

    val workspaceEntity = WorkspaceEntity(
      name = workspaceIngest.name,
      namespace = workspaceIngest.namespace,
      createdDate = Some(format.format(new Date())),
      createdBy = username,
      attributes = Some(Map.empty)
    )

    val pipeline: HttpRequest => Future[HttpResponse] =
      addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive

    val responseFuture: Future[HttpResponse] = pipeline { Post(FireCloudConfig.Workspace.workspacesUrl, workspaceEntity) }

    responseFuture onComplete {
      case Success(response) =>
        response.status match {
          case Created =>
            log.debug("Workspace Created response")
            requestContext.complete(response.status, unmarshal[WorkspaceEntity].apply(response))
            context.stop(self)
          case _ =>
            // Bubble up all other unmarshallable responses
            log.warning("Unanticipated response: " + response.status.defaultMessage)
            requestContext.complete(response)
            context.stop(self)
        }
      case Failure(error) =>
        // Failure accessing service
        log.error(error, "Could not access the workspace service")
        requestContext.failWith(new RequestProcessingException(StatusCodes.InternalServerError, error.getMessage))
        context.stop(self)
    }

  }

}
