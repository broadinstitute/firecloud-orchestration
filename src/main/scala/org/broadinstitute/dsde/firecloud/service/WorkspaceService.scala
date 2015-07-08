package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, Props}
import com.wordnik.swagger.annotations.{Api, ApiImplicitParam, ApiImplicitParams, ApiOperation}
import org.slf4j.LoggerFactory
import spray.client.pipelining.{Get, Post}
import spray.http.StatusCodes.Unauthorized
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing._

import org.broadinstitute.dsde.vault.common.directives.OpenAMDirectives._

import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}

class WorkspaceServiceActor extends Actor with WorkspaceService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

@Api(value = "/workspaces", description = "Workspaces Service",
  produces = "application/json, text/plain")
trait WorkspaceService extends HttpService with FireCloudDirectives {

  private final val ApiPrefix = "workspaces"
  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private implicit val executionContext = actorRefFactory.dispatcher

  val routes = createWorkspaceRoute ~ listWorkspacesRoute

  lazy val log = LoggerFactory.getLogger(getClass)

  @ApiOperation(
    value = "create workspace",
    nickname = "createWorkspace",
    httpMethod = "POST",
    consumes = "application/json, text/plain",
    notes = "The response is forwarded unmodified from the workspaces service.")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      paramType = "body", name = "body", required = true,
      dataType = "org.broadinstitute.dsde.firecloud.model.WorkspaceIngest",
      value = "Workspace to create"
    )
  ))
  def createWorkspaceRoute: Route =
    path(ApiPrefix) {
      post {
        entity(as[String]) { ingest =>
          commonNameFromOptionalCookie() { username => requestContext =>
              username match {
                case Some(x) =>
                  val params = ingest.parseJson.convertTo[Map[String, JsValue]]
                    .updated("namespace", username.get.toJson)
                    .updated("createdBy", username.get.toJson)
                    .updated("createdDate", dateFormat.format(new Date()).toJson)
                    .updated("attributes", JsObject())
                  val request = Post(
                    FireCloudConfig.Workspace.workspaceCreateUrl,
                    HttpClient.createJsonHttpEntity(params.toJson.compactPrint)
                  )
                  actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
                    HttpClient.PerformExternalRequest(request)
                case None =>
                  log.error("No authenticated username provided.")
                  requestContext.complete(Unauthorized)
              }
            }
          }
        }
      }

  @ApiOperation(
    value = "list workspaces",
    nickname = "listWorkspaces",
    httpMethod = "GET",
    notes = "The response is forwarded unmodified from the workspaces service.")
  def listWorkspacesRoute: Route =
    path(ApiPrefix) {
      get { requestContext =>
        actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
          HttpClient.PerformExternalRequest(Get(FireCloudConfig.Workspace.workspacesListUrl))
      }
    }
}
