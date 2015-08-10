package org.broadinstitute.dsde.firecloud.service

import javax.ws.rs.Path

import akka.actor.{Props, Actor}
import com.wordnik.swagger.annotations._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.routing._
import spray.httpx.SprayJsonSupport._

class MethodConfigurationServiceActor extends Actor with MethodConfigurationService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

@Api(value = "/workspaces/{workspaceNamespace}/{workspaceName}/method_configs",
  description = "Method Configuration Services",
  produces = "application/json")
trait MethodConfigurationService extends HttpService with FireCloudDirectives {

  private final val ApiPrefix = "workspaces"
  lazy val routes = methodConfigurationUpdateRoute ~ copyMethodRepositoryConfigurationRoute
  lazy val log = LoggerFactory.getLogger(getClass)

  @Path(value = "/{configNamespace}/{configName}")
  @ApiOperation (
    value="update method configuration in a workspace",
    nickname="updateMethodConfiguration",
    httpMethod="PUT")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "workspaceNamespace", required = true, dataType = "string", paramType = "path",
      value = "Workspace Namespace"),
    new ApiImplicitParam(
      name = "workspaceName", required = true, dataType = "string", paramType = "path", value = "Workspace Name"),
    new ApiImplicitParam(
      name = "configNamespace", required = true, dataType = "string", paramType = "path",
      value = "Configuration Namespace"),
    new ApiImplicitParam(
      name = "configName", required = true, dataType = "string", paramType = "path", value = "Configuration Name"),
    new ApiImplicitParam(
      paramType = "body", name = "body", required = true,
      dataType = "org.broadinstitute.dsde.firecloud.model.MethodConfiguration",
      value = "Method Config to Update"
    )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Successful"),
    new ApiResponse(code = 500, message = "Internal Error")))
  def methodConfigurationUpdateRoute: Route =
    path(ApiPrefix / Segment / Segment / "method_configs" / Segment / Segment) {
      (workspaceNamespace, workspaceName, configNamespace, configName) =>
        put {
          entity(as[MethodConfiguration]) { methodConfig =>
            requestContext =>
              val endpointUrl = FireCloudConfig.Rawls.updateMethodConfigurationUrl.
                format(workspaceNamespace, workspaceName, configNamespace, configName)
              actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
                HttpClient.PerformExternalRequest(Put(endpointUrl, methodConfig))
          }
        }
    }

  @Path(value = "/copyFromMethodRepo")
  @ApiOperation(
    value = "copy a method repository configuration to a workspace",
    nickname = "copyMethodRepositoryConfigurationToWorkspace", httpMethod = "POST",
    notes = "Copy a Method Repository Configuration into a workspace")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "workspaceNamespace", required = true, dataType = "string",
      paramType = "path", value = "Workspace Namespace"),
    new ApiImplicitParam(name = "workspaceName", required = true, dataType = "string",
      paramType = "path", value = "Workspace Name"),
    new ApiImplicitParam(
      paramType = "body", name = "body", required = true, value = "Method Configuration to Copy",
      dataType = "org.broadinstitute.dsde.firecloud.model.CopyConfigurationIngest"
    )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Successful Request"),
    new ApiResponse(code = 403, message = "Source method configuration does not exist"),
    new ApiResponse(code = 409, message = "Destination method configuration by that name already exists"),
    new ApiResponse(code = 422, message = "Error parsing source method configuration"),
    new ApiResponse(code = 500, message = "Internal Error")))
  def copyMethodRepositoryConfigurationRoute: Route =
    path(ApiPrefix / Segment / Segment / "method_configs" / "copyFromMethodRepo") {
      (workspaceNamespace, workspaceName) =>
      post {
        entity(as[CopyConfigurationIngest]) { ingest => requestContext =>
          val copyMethodConfig = new MethodConfigurationCopy(
            methodRepoName = ingest.configurationName,
            methodRepoNamespace = ingest.configurationNamespace,
            methodRepoSnapshotId = ingest.configurationSnapshotId,
            destination = Option(Destination(
              name = ingest.destinationName,
              namespace = ingest.destinationNamespace,
              workspaceName = Option(WorkspaceName(
                namespace = Option(workspaceNamespace),
                name = Option(workspaceName))))))
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(Post(FireCloudConfig.Rawls.copyFromMethodRepoConfigUrl, copyMethodConfig))
        }
      }
    }
}