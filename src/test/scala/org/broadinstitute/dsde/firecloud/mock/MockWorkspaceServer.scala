package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model.Workspace
import org.joda.time.DateTime
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpCallback._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse._
import spray.http.StatusCodes._
import spray.json._

/**
 * Represents potential results that can be returned from the Workspace Service
 */
object MockWorkspaceServer {

  val mockValidWorkspace = Workspace(
    "namespace",
    "name",
    Set.empty,
    "workspace_id",
    "buckety_bucket",
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Map(), //attributes
    Map(), //acls
    Map(), //realm acls
    false //locked
  )

  val mockValidId = randomPositiveInt()
  val mockInvalidId = randomPositiveInt()

  val mockValidSubmission = SubmissionRequest(
    methodConfigurationNamespace = Option(randomAlpha()),
    methodConfigurationName = Option(randomAlpha()),
    entityType = Option(randomAlpha()),
    entityName = Option(randomAlpha()),
    expression = Option(randomAlpha()),
    useCallCache = Option(randomBoolean()),
    workflowFailureMode = Option(randomElement(List("ContinueWhilePossible", "NoNewCalls")))
  ) 
  
  val mockInvalidSubmission = SubmissionRequest(
    methodConfigurationNamespace = Option.empty,
    methodConfigurationName = Option.empty,
    entityType = Option.empty,
    entityName = Option.empty,
    expression = Option.empty,
    useCallCache = Option.empty,
    workflowFailureMode = Option.empty
  )

  val workspaceBasePath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  val notificationsBasePath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.notificationsPath

  var workspaceServer: ClientAndServer = _

  def stopWorkspaceServer(): Unit = {
    workspaceServer.stop()
  }

  def startWorkspaceServer(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)

    // Submissions responses

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/api/submissions/queueStatus")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name))
          .withHeader(authHeader))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidSubmissionCallback")
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/validate"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name))
          .withHeader(authHeader))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidSubmissionCallback")
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions"
          .format(mockValidWorkspace.namespace, mockValidWorkspace.name)))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(Found.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions"
          .format(mockValidWorkspace.namespace, mockValidWorkspace.name))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
          .withBody(mockValidSubmission.toJson.prettyPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("DELETE")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s"
          .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(204)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("DELETE")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s/workflows/%s"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId, mockValidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
          .withBody(mockValidSubmission.toJson.prettyPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s/workflows/%s"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId, mockInvalidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s/workflows/%s/outputs"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId, mockValidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
          .withBody(mockValidSubmission.toJson.prettyPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s/workflows/%s/outputs"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId, mockInvalidId))
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"$notificationsBasePath/workspace/${mockValidWorkspace.namespace}/${mockValidWorkspace.name}")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"$notificationsBasePath/general")
          .withHeader(authHeader))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )
  }

}
