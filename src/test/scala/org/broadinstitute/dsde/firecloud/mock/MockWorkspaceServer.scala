package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model.{GoogleProjectId, GoogleProjectNumber, RawlsBillingAccountName, SubmissionRequest, WorkspaceDetails, WorkspaceVersions}
import org.joda.time.DateTime
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpClassCallback.callback
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse._
import akka.http.scaladsl.model.StatusCodes._
import spray.json._

/**
 * Represents potential results that can be returned from the Workspace Service
 */
//noinspection TypeAnnotation,NameBooleanParameters
object MockWorkspaceServer {

  val mockValidWorkspace = WorkspaceDetails(
    "namespace",
    "name",
    "workspace_id",
    "buckety_bucket",
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Some(Map()), //attributes
    false, //locked
    Some(Set.empty), //authdomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    Option(DateTime.now()),
    None,
    None
  )

  val mockSpacedWorkspace = WorkspaceDetails(
    "spacey",
    "this  workspace has spaces",
    "workspace_id",
    "buckety_bucket",
    Some("wf-collection"),
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Some(Map()), //attributes
    false, //locked
    Some(Set.empty), //authdomain
    WorkspaceVersions.V2,
    GoogleProjectId("googleProject"),
    Some(GoogleProjectNumber("googleProjectNumber")),
    Some(RawlsBillingAccountName("billingAccount")),
    None,
    Option(DateTime.now()),
    None,
    None
  )

  val mockValidId = randomPositiveInt()
  val mockInvalidId = randomPositiveInt()
  val alternativeMockValidId = randomPositiveInt()

  val mockValidSubmission = OrchSubmissionRequest(
    methodConfigurationNamespace = Option(randomAlpha()),
    methodConfigurationName = Option(randomAlpha()),
    entityType = Option(randomAlpha()),
    entityName = Option(randomAlpha()),
    expression = Option(randomAlpha()),
    useCallCache = Option(randomBoolean()),
    deleteIntermediateOutputFiles = Option(randomBoolean()),
    useReferenceDisks = Option(randomBoolean()),
    userComment = Option("This submission came from a mock server."),
    memoryRetryMultiplier = Option(1.1d),
    workflowFailureMode = Option(randomElement(List("ContinueWhilePossible", "NoNewCalls")))
  )

  val mockInvalidSubmission = OrchSubmissionRequest(
    methodConfigurationNamespace = Option.empty,
    methodConfigurationName = Option.empty,
    entityType = Option.empty,
    entityName = Option.empty,
    expression = Option.empty,
    useCallCache = Option.empty,
    deleteIntermediateOutputFiles = Option.empty,
    useReferenceDisks = Option.empty,
    userComment = Option("This invalid submission came from a mock server."),
    memoryRetryMultiplier = Option(1.1d),
    workflowFailureMode = Option.empty
  )

  val workspaceBasePath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  val notificationsBasePath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.notificationsPath

  // Mapping from submission ID to the status code and response which the mock server should return when the PATCH endpoint is called
  val submissionIdPatchResponseMapping = List(
    (mockValidId, OK, mockValidSubmission.toJson.prettyPrint),
    (alternativeMockValidId, BadRequest, MockUtils.rawlsErrorReport(BadRequest).toJson.compactPrint),
    (mockInvalidId, NotFound, MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
  )

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
          .withPath("/api/submissions/queueStatus"))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissionsCount"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name)))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          // presence of auth header will differentiate this mock response from the one at line 137
          .withHeader(authHeader)
          .withPath(s"${workspaceBasePath}/%s/%s/submissions"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name)))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidSubmissionCallback")
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/validate"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name)))
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
          .format(mockValidWorkspace.namespace, mockValidWorkspace.name)))
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
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId)))
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
          .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId)))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(204)
      )

    MockWorkspaceServer.submissionIdPatchResponseMapping.foreach { case (id, responseCode, responseContent) =>
      MockWorkspaceServer.workspaceServer
        .when(
          request()
            .withMethod("PATCH")
            .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s"
              .format(mockValidWorkspace.namespace, mockValidWorkspace.name, id)))
        .respond(
          response()
            .withHeaders(header)
            .withStatusCode(responseCode.intValue)
            .withBody(responseContent)
        )
    }

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"${workspaceBasePath}/%s/%s/submissions/%s"
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId)))
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
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId)))
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
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId, mockValidId)))
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
            .format(mockSpacedWorkspace.namespace, mockSpacedWorkspace.name, mockValidId, mockValidId)))
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
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId, mockInvalidId)))
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
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockValidId, mockValidId)))
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
            .format(mockValidWorkspace.namespace, mockValidWorkspace.name, mockInvalidId, mockInvalidId)))
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
          .withPath(s"$notificationsBasePath/workspace/${mockValidWorkspace.namespace}/${mockValidWorkspace.name}"))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )

    MockWorkspaceServer.workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(s"$notificationsBasePath/general"))
      .respond(
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
      )
  }

}
