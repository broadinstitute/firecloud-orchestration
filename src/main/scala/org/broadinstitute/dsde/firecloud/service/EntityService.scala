package org.broadinstitute.dsde.firecloud.service

import javax.ws.rs.Path

import akka.actor.{Actor, Props}
import com.wordnik.swagger.annotations._
import org.broadinstitute.dsde.firecloud.core.{GetEntitiesWithTypeActor, GetEntitiesWithType}
import org.broadinstitute.dsde.firecloud.model.Entity
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.routing._

class EntityServiceActor extends Actor with EntityService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

@Api(
  value = "/workspaces/{workspaceNamespace}/{workspaceName}/entities",
  description = "Entity Service",
  position = 2,
  produces = "application/json")
trait EntityService extends HttpService with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  val routes = listEntityTypesRoute ~ listEntitiesPerTypeRoute ~ listEntitiesWithTypeRoute
  lazy val log = LoggerFactory.getLogger(getClass)

  /**
   * This endpoint collects all workspace entities and entity types into a list of EntityWithTypes.
   */
  def listEntitiesWithTypeRoute: Route =
    path("workspaces" / Segment / Segment / "entities_with_type") {
      (workspaceNamespace, workspaceName) =>
        get { requestContext =>
          val url = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
          val ewtActor = actorRefFactory.actorOf(Props(new GetEntitiesWithTypeActor(requestContext)), "EntitiesWithTypeActor")
          ewtActor ! GetEntitiesWithType.ProcessUrl(url)
        }
    }

  @ApiOperation(value = "list all entity types in a workspace",
    nickname = "listEntityTypes",
    httpMethod = "GET",
    produces = "application/json",
    response = classOf[String],
    responseContainer = "List")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "workspaceNamespace",
      required = true,
      dataType = "string",
      paramType = "path",
      value = "Workspace Namespace"),
    new ApiImplicitParam(
      name = "workspaceName",
      required = true,
      dataType = "string",
      paramType = "path",
      value = "Workspace Name")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Successful Request"),
    new ApiResponse(code = 404, message = "Workspace does not exist"),
    new ApiResponse(code = 500, message = "Internal Error")
  ))
  def listEntityTypesRoute: Route =
    path("workspaces" / Segment / Segment / "entities") {
      (workspaceNamespace, workspaceName) =>
        get { requestContext =>
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(Get(FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)))
        }
    }

  @Path("/{entityType}")
  @ApiOperation(value = "list all entities of given type in a workspace",
    nickname = "listEntities",
    httpMethod = "GET",
    produces = "application/json",
    response = classOf[Entity],
    responseContainer = "List",
    notes = "response is list of entities from a workspace using the workspace service")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "workspaceNamespace",
      required = true,
      dataType = "string",
      paramType = "path",
      value = "Workspace Namespace"),
    new ApiImplicitParam(
      name = "workspaceName",
      required = true,
      dataType = "string",
      paramType = "path",
      value = "Workspace Name"),
    new ApiImplicitParam(
      name = "entityType",
      required = true,
      dataType = "string",
      paramType = "path",
      value = "Entity Type")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Successful Request"),
    new ApiResponse(code = 404, message = "Workspace or entityType does not exist"),
    new ApiResponse(code = 500, message = "Internal Error")
  ))
  def listEntitiesPerTypeRoute: Route =
    path("workspaces" / Segment / Segment / "entities" / Segment) {
      (workspaceNamespace, workspaceName, entityType) =>
        get { requestContext =>
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(Get(s"${FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)}/$entityType"))
        }
      }
}
