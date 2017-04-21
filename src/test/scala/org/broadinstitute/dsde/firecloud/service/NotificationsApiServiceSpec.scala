package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.webservice.NotificationsApiService
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import spray.http.StatusCodes
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._

class NotificationsApiServiceSpec extends FlatSpec with BeforeAndAfterAll with HttpService with ScalatestRouteTest with NotificationsApiService with Matchers with FireCloudRequestBuilding {
  def actorRefFactory = system

  implicit val routeTestTimeout = RouteTestTimeout(5.seconds)

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  "NotificationsApiService" should "get workspace notifications" in {
    Get(s"/api/notifications/workspace/${MockWorkspaceServer.mockValidWorkspace.namespace}/${MockWorkspaceServer.mockValidWorkspace.name}") ~> dummyAuthHeaders ~>
      sealRoute(notificationsRoutes) ~>
      check {
        assertResult(StatusCodes.OK, response.entity.asString) {
          status
        }
      }
  }

  it should "get general notifications" in {
    Get(s"/api/notifications/general") ~> dummyAuthHeaders ~>
      sealRoute(notificationsRoutes) ~>
      check {
        assertResult(StatusCodes.OK, response.entity.asString) {
          status
        }
      }
  }
}
