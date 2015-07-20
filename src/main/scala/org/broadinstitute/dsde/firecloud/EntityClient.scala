package org.broadinstitute.dsde.firecloud

import java.text.SimpleDateFormat

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Try, Failure, Success}

import akka.actor.{ActorSystem, Actor, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.EntityClient._
import org.broadinstitute.dsde.firecloud.model.{EntityCreateResult, Entity}
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.http._
import spray.routing.RequestContext
import spray.json._

import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

object EntityClient {
  case class EntityListRequest(workspaceNamespace: String,
                               workspaceName: String,
                               entityType: String)

  case class CreateEntities(workspaceNamespace: String,
                            workspaceName: String,
                            entities: Seq[Entity])

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
    case CreateEntities(workspaceNamespace: String, workspaceName: String, entities: Seq[Entity]) =>
      createEntities(workspaceNamespace, workspaceName, entities)
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

  def createEntities(wsNamespace: String, wsName: String, entities: Seq[Entity]) = {
    val results = entities.map(entity => createEntityOrReportError(wsNamespace, wsName, entity))
    // have to manually construct an HttpResponse here because we're aggregating the results of multiple calls to Rawls
    // TODO the response code should indicate if any of the individual requests were failures
    val response = HttpResponse(OK, HttpClient.createJsonHttpEntity(results.toJson.compactPrint))
    requestContext.complete(response)
  }

  def createEntityOrReportError(workspaceNamespace: String, workspaceName: String, entity: Entity) = {
    (entity.entityType, entity.entityName) match {
      case (Some(entityType), Some(entityName)) => {
        val url = FireCloudConfig.Workspace.entityPathFromWorkspace(workspaceNamespace, workspaceName)
        val externalRequest = Post(url, HttpClient.createJsonHttpEntity(entity.toJson.compactPrint))
        val pipeline = addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive
        // TODO figure out how to get the response in a non-blocking way,
        // TODO given that the requestContext should not complete until *all* are finished?
        Try(Await.result(pipeline(externalRequest), Duration.Inf)) match {
          case Success(response) => response.status match {
            case StatusCodes.Created => EntityCreateResult(entityType, entityName, true, "Entity created successfully")
            case _ => EntityCreateResult(entityType, entityName, false, s"Bad response from workspace service: ${response.message}")
          }
          case Failure(e: Exception) => EntityCreateResult(entityType, entityName, false,
            s"Error sending request to workspace service: ${e.getMessage}")
        }
      }
      case _ => EntityCreateResult(entity.entityType.getOrElse("Missing type"), entity.entityName.getOrElse("Missing name"), false,
        "Entity missing type and/or name")
    }
  }
}

