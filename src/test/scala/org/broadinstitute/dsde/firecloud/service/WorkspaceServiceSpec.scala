package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.MockServers
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{WorkspaceEntity, WorkspaceIngest}
import org.broadinstitute.dsde.vault.common.openam.OpenAMSession
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.http.HttpCookie
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.testkit.ScalatestRouteTest

class WorkspaceServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with WorkspaceService {

  def actorRefFactory = system

  private final val ApiPrefix = "/workspaces"

  override def beforeAll(): Unit = {
    MockServers.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockServers.stopWorkspaceServer()
  }

  "WorkspaceService" - {

    val openAMSession = OpenAMSession(()).futureValue(timeout(Span(5, Seconds)), interval(scaled(Span(0.5, Seconds))))
    val token = openAMSession.cookies.head.content
    val workspaceIngest = WorkspaceIngest(
      name = Some(MockServers.randomAlpha()),
      namespace = Some(MockServers.randomAlpha()))

    "when calling POST on the workspaces path with a valid WorkspaceIngest" - {
      "valid workspace is returned" in {
        Post(ApiPrefix, workspaceIngest) ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(createRoute) ~> check {
          status should equal(Created)
          val entity = responseAs[WorkspaceEntity]
          entity.name shouldNot be(Option.empty)
          entity.namespace shouldNot be(Option.empty)
          entity.createdBy shouldNot be(Option.empty)
          entity.createdDate shouldNot be(Option.empty)
          entity.attributes should be(Some(Map.empty))
        }
      }
    }

    "when calling POST on the workspaces path without a valid authentication token" - {
      "Unauthorized (401) response is returned" in {
        Post(ApiPrefix, workspaceIngest) ~> sealRoute(createRoute) ~> check {
          status should equal(Unauthorized)
        }
      }
    }

    "when calling GET on the workspaces path" - {
      "MethodNotAllowed error is returned" in {
        Get(ApiPrefix) ~> sealRoute(createRoute) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

    "when calling PUT on the workspaces path" - {
      "MethodNotAllowed error is returned" in {
        Put(ApiPrefix) ~> sealRoute(createRoute) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

  }

}
