package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.webservice.NotificationsApiService
import spray.http.{HttpMethod, StatusCode}
import spray.http.HttpMethods.GET
import spray.http.StatusCodes.{MethodNotAllowed, OK}

final class NotificationsApiServiceSpec extends ServiceSpec with NotificationsApiService {

  def actorRefFactory = system

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
  }

  private def doAssert(method: HttpMethod, uri: String, expectedStatus: StatusCode): Unit = {
    new RequestBuilder(method)(uri) ~> dummyAuthHeaders ~> sealRoute(notificationsRoutes) ~> check {
      status should be(expectedStatus)
    }
  }
}
