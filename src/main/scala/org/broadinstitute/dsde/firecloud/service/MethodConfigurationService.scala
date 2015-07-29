package org.broadinstitute.dsde.firecloud.service

import javax.ws.rs.Path

import akka.actor.{Props, Actor}
import com.wordnik.swagger.annotations._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{WorkspaceName, Destination, MethodConfigurationCopy}
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
  lazy val routes = copyMethodRepositoryConfigurationRoute
  lazy val log = LoggerFactory.getLogger(getClass)

  @Path(value = "/copyFromMethodRepo")
  @ApiOperation(
    value = "copy a method repository configuration to a workspace",
    nickname = "copyMethodRepositoryConfigurationToWorkspace",
    httpMethod = "POST",
    notes = "Copy a Method Repository Configuration into a workspace")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "workspaceNamespace", required = true, dataType = "string", paramType = "path",
      value = "Workspace Namespace"),
    new ApiImplicitParam(name = "workspaceName", required = true, dataType = "string", paramType = "path",
      value = "Workspace Name"),
    new ApiImplicitParam(name = "configurationNamespace", required = true, dataType = "string", paramType = "form",
      value = "Configuration Namespace"),
    new ApiImplicitParam(name = "configurationName", required = true, dataType = "string", paramType = "form",
      value = "Configuration Name"),
    new ApiImplicitParam(name = "configurationSnapshot", required = true, dataType = "string", paramType = "form",
      value = "Configuration Snapshot ID"),
    new ApiImplicitParam(name = "destinationNamespace", required = true, dataType = "string", paramType = "form",
      value = "New Configuration Namespace within user's workspace"),
    new ApiImplicitParam(name = "destinationName", required = true, dataType = "string", paramType = "form",
      value = "New Configuration Name within user's workspace")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 201, message = "Successful Request"),
    new ApiResponse(code = 403, message = "Source method configuration does not exist"),
    new ApiResponse(code = 409, message = "Destination method configuration by that name already exists"),
    new ApiResponse(code = 422, message = "Error parsing source method configuration"),
    new ApiResponse(code = 500, message = "Internal Error")))
  def copyMethodRepositoryConfigurationRoute: Route =
    path(ApiPrefix / Segment / Segment / "method_configs" / "copyFromMethodRepo") { (workspaceNamespace, workspaceName) =>
      post {
        // TODO: Refactor this into consuming a json object. Look at tests and mock responses.
        formFields('configurationNamespace, 'configurationName, 'configurationSnapshot, 'destinationNamespace, 'destinationName) {
          (configurationNamespace, configurationName, configurationSnapshot, destinationNamespace, destinationName) =>
            respondWithJSON { requestContext =>
              val copyMethodConfig = new MethodConfigurationCopy(
                methodRepoName = Option(configurationName),
                methodRepoNamespace = Option(configurationNamespace),
                methodRepoSnapshotId = Option(configurationSnapshot),
                destination = Option(Destination(
                  name = Option(destinationName),
                  namespace = Option(destinationNamespace),
                  workspaceName = Option(WorkspaceName(
                    namespace = Option(workspaceNamespace),
                    name = Option(workspaceName))))))
              actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
                HttpClient.PerformExternalRequest(Post(FireCloudConfig.Workspace.copyFromMethodRepoConfigUrl, copyMethodConfig))
            }
        }
      }
    }
}