package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SubmissionIngest
import org.broadinstitute.dsde.vault.common.openam.OpenAMSession
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.http.HttpCookie
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest

class SubmissionServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with SubmissionService {

  def actorRefFactory = system

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  val localSubmissionsPath = s"/workspaces/%s/%s/submissions".format(
    MockWorkspaceServer.mockValidWorkspace.namespace.get,
    MockWorkspaceServer.mockValidWorkspace.name.get)

  val localSubmissionIdPath = s"/workspaces/%s/%s/submissions/%s".format(
    MockWorkspaceServer.mockValidWorkspace.namespace.get,
    MockWorkspaceServer.mockValidWorkspace.name.get,
    MockWorkspaceServer.mockValidId)

  val localInvalidSubmissionIdPath = s"/workspaces/%s/%s/submissions/%s".format(
    MockWorkspaceServer.mockValidWorkspace.namespace.get,
    MockWorkspaceServer.mockValidWorkspace.name.get,
    MockWorkspaceServer.mockValidId + MockUtils.randomPositiveInt())

  "SubmissionService" - {

    val openAMSession = OpenAMSession(()).futureValue(timeout(Span(5, Seconds)), interval(scaled(Span(0.5, Seconds))))
    val token = openAMSession.cookies.head.content

    "when calling GET on the /workspaces/*/*/submissions path" - {
      "a list of submissions is returned" in {
        (Get(localSubmissionsPath)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))) ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions path with a valid submission" - {
      "OK response is returned" in {
        (Post(localSubmissionsPath, MockWorkspaceServer.mockValidSubmission)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))) ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val submission = responseAs[SubmissionIngest]
          submission shouldNot be (None)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/submissions path with an invalid submission" - {
      "BadRequest response is returned" in {
        (Post(localSubmissionsPath, MockWorkspaceServer.mockInvalidSubmission)
          ~> Cookie(HttpCookie("iPlanetDirectoryPro", token))) ~> sealRoute(routes) ~> check {
          status should equal(BadRequest)
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
        Get(localSubmissionIdPath) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/submissions/* path with an invalid id" - {
      "NotFound response is returned" in {
        Get(localInvalidSubmissionIdPath) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
        }
      }
    }

  }

}
