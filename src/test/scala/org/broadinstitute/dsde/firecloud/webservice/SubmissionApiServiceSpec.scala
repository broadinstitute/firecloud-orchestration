package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.OrchSubmissionRequest
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec

import scala.concurrent.ExecutionContext

final class SubmissionApiServiceSpec extends BaseServiceSpec with SubmissionApiService with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  val localSubmissionsCountPath = FireCloudConfig.Rawls.submissionsCountPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name)

  val localSubmissionsPath = FireCloudConfig.Rawls.submissionsPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name)

  val localSubmissionIdPath = FireCloudConfig.Rawls.submissionsIdPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name,
    MockWorkspaceServer.mockValidId)

  val localInvalidSubmissionIdPath = FireCloudConfig.Rawls.submissionsIdPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name,
    MockWorkspaceServer.mockInvalidId)

  val localSubmissionWorkflowIdPath = FireCloudConfig.Rawls.submissionsWorkflowIdPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name,
    MockWorkspaceServer.mockValidId,
    MockWorkspaceServer.mockValidId)

  val localSpacedWorkspaceWorkflowIdPath = FireCloudConfig.Rawls.submissionsWorkflowIdPath.format(
    MockWorkspaceServer.mockSpacedWorkspace.namespace,
    MockWorkspaceServer.mockSpacedWorkspace.name,
    MockWorkspaceServer.mockValidId,
    MockWorkspaceServer.mockValidId)

  val localInvalidSubmissionWorkflowIdPath = FireCloudConfig.Rawls.submissionsWorkflowIdPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name,
    MockWorkspaceServer.mockInvalidId,
    MockWorkspaceServer.mockInvalidId)

  val localSubmissionWorkflowIdOutputsPath = FireCloudConfig.Rawls.submissionsWorkflowIdOutputsPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name,
    MockWorkspaceServer.mockValidId,
    MockWorkspaceServer.mockValidId)

  val localInvalidSubmissionWorkflowIdOutputsPath = FireCloudConfig.Rawls.submissionsWorkflowIdOutputsPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name,
    MockWorkspaceServer.mockInvalidId,
    MockWorkspaceServer.mockInvalidId)

  "SubmissionApiService" - {
    "when hitting the /submissions/queueStatus path" - {
      "with GET" - {
        "OK status is returned" in {
          Get("/submissions/queueStatus") ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissionsCount path" - {
      "OK status is returned" in {
        (Get(localSubmissionsCountPath)
          ~> dummyAuthHeaders) ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions path" - {
      "a list of submissions is returned" in {
        (Get(localSubmissionsPath)
          ~> dummyAuthHeaders) ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(OK)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions path with a valid submission" - {
      "OK response is returned" in {
        (Post(localSubmissionsPath, MockWorkspaceServer.mockValidSubmission)
          ~> dummyAuthHeaders) ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(OK)
            val submission = responseAs[OrchSubmissionRequest]
            submission shouldNot be (None)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions path with an invalid submission" - {
      "BadRequest response is returned" in {
        (Post(localSubmissionsPath, MockWorkspaceServer.mockInvalidSubmission)
          ~> dummyAuthHeaders) ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(BadRequest)
            errorReportCheck("Rawls", BadRequest)
        }
      }
    }

    /* TODO: this test really only checks that we've set up our mock server correctly, it does not
        validate any real authentication. It should be re-evaluated and possibly deleted.
     */
    "when calling POST on the /workspaces/*/*/submissions path without a valid authentication token" - {
      "Found (302 redirect) response is returned" in {
        Post(localSubmissionsPath, MockWorkspaceServer.mockValidSubmission) ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(Found)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions/validate path" - {
      "with a valid submission, OK response is returned" in {
        (Post(s"$localSubmissionsPath/validate", MockWorkspaceServer.mockValidSubmission)
          ~> dummyAuthHeaders) ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(OK)
            val submission = responseAs[OrchSubmissionRequest]
            submission shouldNot be (None)
        }
      }

      "with an invalid submission, BadRequest response is returned" in {
        (Post(s"$localSubmissionsPath/validate", MockWorkspaceServer.mockInvalidSubmission)
          ~> dummyAuthHeaders) ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(BadRequest)
            errorReportCheck("Rawls", BadRequest)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions/* path with a valid id" - {
      "OK response is returned" in {
        Get(localSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions/* path with an invalid id" - {
      "NotFound response is returned" in {
        Get(localInvalidSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling DELETE on the /workspaces/*/*/submissions/* with a valid id" - {
      "OK response is returned" in {
        Delete(localSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(NoContent)
        }
      }
    }

    "when calling DELETE on the /workspaces/*/*/submissions/* with an invalid id" - {
      "NotFound response is returned" in {
        Delete(localInvalidSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling PATCH on the /workspaces/*/*/submissions/* path" - {
      MockWorkspaceServer.submissionIdPatchResponseMapping.foreach { case (id, expectedResponseCode, _) =>
        s"HTTP $expectedResponseCode responses are forwarded back correctly" in {
          val submissionIdPath = FireCloudConfig.Rawls.submissionsIdPath.format(
            MockWorkspaceServer.mockValidWorkspace.namespace,
            MockWorkspaceServer.mockValidWorkspace.name,
            id)

          (Patch(submissionIdPath, "PATCH request body. The mock server will ignore this content and respond " +
            "entirely based on submission ID instead")
            ~> dummyAuthHeaders) ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(expectedResponseCode)
            if (status != OK) {
              errorReportCheck("Rawls", status)
            }
          }
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions/*/workflows/* path" - {
      "with a valid id, OK response is returned" in {
        Get(localSubmissionWorkflowIdPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(OK)
        }
      }

      "with a valid id and a space in the workspace name, OK response is returned" in {
        // the request inbound to orchestration should encoded, so we replace spaces with  %20 in the test below.
        // this test really verifies that the runtime orch code can accept an encoded URI and maintain the encoding
        // when it passes through the request to rawls - i.e. it doesn't decode the request at any point.
        Get(localSpacedWorkspaceWorkflowIdPath.replace(" ","%20")) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(OK)
        }
      }

      "with an invalid id, NotFound response is returned" in {
        Get(localInvalidSubmissionWorkflowIdPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions/*/workflows/*/outputs path" - {
      "with a valid id, OK response is returned" in {
        Get(localSubmissionWorkflowIdOutputsPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(OK)
        }
      }

      "with an invalid id, NotFound response is returned" in {
        Get(localInvalidSubmissionWorkflowIdOutputsPath) ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }
  }
}
