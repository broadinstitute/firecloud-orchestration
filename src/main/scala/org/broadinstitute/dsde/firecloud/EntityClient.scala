package org.broadinstitute.dsde.firecloud

/**
 * Created by mbemis on 7/10/15.
 */
import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.EntityClient.EntityListRequest
import org.broadinstitute.dsde.firecloud.WorkspaceClient.{WorkspaceCreate, WorkspacesListRequest}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{WorkspaceEntity, WorkspaceIngest}
import org.broadinstitute.dsde.firecloud.service.ServiceUtils
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.http.{HttpRequest, HttpResponse, RequestProcessingException, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}


object EntityClient {

  case class EntityListRequest(workspaceNamespace: String, workspaceName: String, entityType: String)

  def props(requestContext: RequestContext): Props = Props(new EntityClient(requestContext))

}

class EntityClient (requestContext: RequestContext) extends Actor {

  import system.dispatcher

  implicit val system = context.system
  val log = Logging(system, getClass)
  val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ")

  override def receive: Receive = {
    case EntityListRequest(workspaceNamespace: String, workspaceName: String, entityType: String) => listEntities(workspaceNamespace, workspaceName, entityType)
  }

  def listEntities(workspaceNamespace: String, workspaceName: String, entityType: String): Unit = {
    log.info("listEntities request received")
    def completeSuccessfully(requestContext: RequestContext, response: HttpResponse): Unit = {
      requestContext.complete(response)
    }
    ServiceUtils.completeFromExternalRequest(ServiceUtils.ExternalRequestParams(
      log,
      context,
      formUrl(workspaceNamespace, workspaceName) + "/" + entityType,
      requestContext,
      completeSuccessfully
    ))
  }

  def formUrl(workspaceNamespace: String, workspaceName: String): String = {
    s"${FireCloudConfig.Workspace.baseUrl}/workspaces/${workspaceNamespace}/${workspaceName}/entities"
  }
}

