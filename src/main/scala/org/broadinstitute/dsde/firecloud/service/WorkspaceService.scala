package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import com.wordnik.swagger.annotations._
import org.broadinstitute.dsde.firecloud.WorkspaceClient
import org.broadinstitute.dsde.firecloud.model.{WorkspaceEntity, WorkspaceIngest}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._
import org.broadinstitute.dsde.vault.common.directives.OpenAMDirectives._

class WorkspaceServiceActor extends Actor with WorkspaceService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

@Api(value = "/workspaces", description = "Workspace Services", produces = "application/json")
trait WorkspaceService extends HttpService with FireCloudDirectives {

  private final val ApiPrefix = "workspaces"
  private implicit val executionContext = actorRefFactory.dispatcher

  val routes = createWorkspaceRoute

  lazy val log = LoggerFactory.getLogger(getClass)

  @ApiOperation(
    value = "create workspace",
    nickname = "createWorkspace",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json",
    response = classOf[WorkspaceEntity],
    notes = "response is a the workspace created by the workspace service")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "body", required = true, dataType = "org.broadinstitute.dsde.firecloud.model.WorkspaceIngest", paramType = "body", value = "Workspace to create")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Created"),
    new ApiResponse(code = 400, message = "Bad Request"),
    new ApiResponse(code = 401, message = "Unauthorized"),
    new ApiResponse(code = 500, message = "Internal Error")))
  def createWorkspaceRoute: Route =
    path(ApiPrefix) {
      post {
        entity(as[WorkspaceIngest]) { ingest =>
          // TODO: Revisit getting the username from the auth token if the rawls service removes it from their API.
          commonNameFromOptionalCookie() { username =>
            respondWithJSON { requestContext =>
              ingest match {
                case x if x.name.isEmpty || x.namespace.isEmpty =>
                  log.error("Invalid workspace ingest object.")
                  requestContext.complete(
                    BadRequest,
                    (if (x.name.isEmpty) " name is a required field;" else "") + (if (x.namespace.isEmpty) " namespace is a required field;" else "")
                  )
                case _ =>
                  username match {
                    case Some(x) =>
                      val workspaceClient = actorRefFactory.actorOf(Props(new WorkspaceClient(requestContext)))
                      workspaceClient ! WorkspaceClient.WorkspaceCreate(ingest, username)
                    case None =>
                      log.error("No authenticated username provided.")
                      requestContext.complete(Unauthorized)
                  }
              }
            }
          }
        }
      }
    }

}