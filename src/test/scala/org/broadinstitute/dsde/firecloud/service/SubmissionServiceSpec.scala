package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model.SubmissionIngest
import spray.http.StatusCodes._

import spray.httpx.SprayJsonSupport._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

class SubmissionServiceSpec extends ServiceSpec with SubmissionService {

  def actorRefFactory = system

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  val localSubmissionsPath = submissionsPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace.get,
    MockWorkspaceServer.mockValidWorkspace.name.get)

  val localSubmissionIdPath = localSubmissionsPath + "/%s".format(MockWorkspaceServer.mockValidId)

  val localInvalidSubmissionIdPath = localSubmissionsPath + "/%s".format(
    MockWorkspaceServer.mockInvalidId)

  "SubmissionService" - {

    "when calling GET on the /workspaces/*/*/submissions path" - {
      "a list of submissions is returned" in {
        (Get(localSubmissionsPath)
          ~> dummyAuthHeaders) ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions path with a valid submission" - {
      "OK response is returned" in {
        (Post(localSubmissionsPath, MockWorkspaceServer.mockValidSubmission)
          ~> dummyAuthHeaders) ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val submission = responseAs[SubmissionIngest]
          submission shouldNot be (None)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions path with an invalid submission" - {
      "BadRequest response is returned" in {
        (Post(localSubmissionsPath, MockWorkspaceServer.mockInvalidSubmission)
          ~> dummyAuthHeaders) ~> sealRoute(routes) ~> check {
          status should equal(BadRequest)
          errorReportCheck("Rawls", BadRequest)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions path without a valid authentication token" - {
      "Found (302 redirect) response is returned" in {
        Post(localSubmissionsPath, MockWorkspaceServer.mockValidSubmission) ~> sealRoute(routes) ~> check {
          status should equal(Found)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions/* path with a valid id" - {
      "OK response is returned" in {
        Get(localSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions/* path with an invalid id" - {
      "NotFound response is returned" in {
        Get(localInvalidSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling DELETE on the /workspaces/*/*/submissions/* with a valid id" - {
      "OK response is returned" in {
        Delete(localSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NoContent)
        }
      }
    }

    "when calling DELETE on the /workspaces/*/*/submissions/* with an invalid id" - {
      "NotFound response is returned" in {
        Delete(localInvalidSubmissionIdPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

  }

}
