package org.broadinstitute.dsde.firecloud.service

/**
 * Created by mbemis on 7/10/15.
 */

import javax.ws.rs.Path

import akka.actor.{Actor, Props}
import com.wordnik.swagger.annotations._
import org.broadinstitute.dsde.firecloud.EntityClient
import org.broadinstitute.dsde.firecloud.model.Entity
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._
import org.broadinstitute.dsde.vault.common.directives.OpenAMDirectives._

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

  private final val ApiPrefix = "entities"
  private implicit val executionContext = actorRefFactory.dispatcher

  val routes = listEntitiesPerTypeRoute

  lazy val log = LoggerFactory.getLogger(getClass)

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
      get {
        respondWithJSON { requestContext =>
          val entityClient = actorRefFactory.actorOf(Props(new EntityClient(requestContext)))
          entityClient ! EntityClient.EntityListRequest(
            workspaceNamespace,
            workspaceName,
            entityType)
        }
      }
    }
}
