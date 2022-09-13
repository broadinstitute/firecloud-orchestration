package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.StatusCodes.{MethodNotAllowed, NotFound, OK}
import akka.http.scaladsl.model.{HttpMethod, StatusCode}
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec

import scala.concurrent.ExecutionContext

final class NotificationsApiServiceSpec extends BaseServiceSpec with NotificationsApiService {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  "NotificationsApiService" - {
    "get workspace notifications" in {
      val namespace = MockWorkspaceServer.mockValidWorkspace.namespace
      val name = MockWorkspaceServer.mockValidWorkspace.name
      val workspaceNotificationUri = s"/api/notifications/workspace/$namespace/$name"

      doAssert(GET, workspaceNotificationUri, OK)
    }

    "get general notifications" in {
      doAssert(GET, "/api/notifications/general", OK)
    }

    "non-GET methods should be rejected" in {
      allHttpMethodsExcept(GET) foreach { method =>
        doAssert(method, "/api/notifications/general", MethodNotAllowed)
      }
    }

    "edge-case URIs should be rejected" in {
      val edgeCaseURIs = Seq(
        "/api/notifications",
        "/api/notifications/somethingNotValid",
        "/api/notifications/workspace/workspaceNamespace",
        "/api/notifications/workspace/workspaceNamespace/workspaceName/something"
      )

      edgeCaseURIs foreach { uri =>
        doAssert(GET, uri, NotFound)
      }
    }
  }

  private def doAssert(method: HttpMethod, uri: String, expectedStatus: StatusCode): Unit = {
    new RequestBuilder(method)(uri) ~> dummyAuthHeaders ~> sealRoute(notificationsRoutes) ~> check {
      status should be(expectedStatus)
    }
  }
}
