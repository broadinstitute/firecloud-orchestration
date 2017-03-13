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
    None,
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

  val mockValidSubmission = SubmissionIngest(
    methodConfigurationNamespace = Option(randomAlpha()),
    methodConfigurationName = Option(randomAlpha()),
    entityType = Option(randomAlpha()),
    entityName = Option(randomAlpha()),
    expression = Option(randomAlpha()),
    readFromCache = Option(randomBoolean())
  ) 
  
  val mockInvalidSubmission = SubmissionIngest(
    methodConfigurationNamespace = Option.empty,
    methodConfigurationName = Option.empty,
    entityType = Option.empty,
    entityName = Option.empty,
    expression = Option.empty,
    readFromCache = Option.empty
  )

  val workspaceBasePath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath

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

  }

}
