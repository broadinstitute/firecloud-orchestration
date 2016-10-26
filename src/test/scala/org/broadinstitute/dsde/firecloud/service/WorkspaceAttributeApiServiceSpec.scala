package org.broadinstitute.dsde.firecloud.service

import java.util.UUID

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.MockAgoraDAO
import org.broadinstitute.dsde.firecloud.mock.{MockTSVFormData, MockUtils}
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RawlsWorkspace, UserInfo, WorkspaceEntity}
import org.broadinstitute.dsde.firecloud.webservice.NamespaceApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

class WorkspaceAttributeApiServiceSpec extends BaseServiceSpec with WorkspaceAttributeApiService  {

  val workspaceAttributeServiceConstructor: (UserInfo) => WorkspaceAttributeService = WorkspaceAttributeService.constructor(app)

  def actorRefFactory = system

  val workspace = WorkspaceEntity(
    Some("namespace"),
    Some("name")
  )


  val testUUID = UUID.randomUUID()

  val testWorkspace = new RawlsWorkspace(workspaceId=testUUID.toString,
    namespace="testWorkspaceNamespace",
    name="testWorkspaceName",
    isLocked=Some(false),
    createdBy="createdBy",
    createdDate="createdDate",
    lastModified=None,
    attributes=Map.empty,
    bucketName="bucketName",
    accessLevels=Map.empty,
    realm=None)

  private final val tsvImportPath = FireCloudConfig.Rawls.importWorkspaceAttributes(testWorkspace.namespace, testWorkspace.name)
  private final val tsvExportPath = FireCloudConfig.Rawls.exportWorkspaceAttributes(testWorkspace.namespace, testWorkspace.name)
  private final val workspacePath = FireCloudConfig.Rawls.workspacesPath

  val urls = List("/api/workspaces/workspaceNamespace/workspaceName/updateAttributes",
    "/api/workspaces/workspaceNamespace/workspaceName/importAttributes",
    "/api/workspaces/workspaceNamespace/workspaceName/exportAttributes")

  val fcPermissions = List(AgoraPermissionHandler.toFireCloudPermission(MockAgoraDAO.agoraPermission))
  var workspaceServer: ClientAndServer = _


  workspaceServer
    .when(
      request()
        .withMethod("PATCH")
        .withPath(s"${workspacePath}/%s/%s"
          .format(workspace.namespace.get, workspace.name.get))
        .withHeader(authHeader))
    .respond(
      org.mockserver.model.HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withStatusCode(NoContent.intValue)
    )

  "WorkspaceAttributeApiService" - {

    "when calling POST on the workspaces/*/*/importAttributes path" - {
      "should 200 OK if it has the correct headers and valid internals" in {
        (Post(tsvImportPath, MockTSVFormData.addNewWorkspaceAttributes)
          ~> dummyAuthHeaders
          ~> sealRoute(namespaceRoutes) ~> check {
          status should equal(OK)
        })
      }
    }

  }

}
