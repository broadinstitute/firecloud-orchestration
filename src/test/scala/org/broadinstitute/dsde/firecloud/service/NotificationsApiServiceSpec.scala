package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.webservice.NotificationsApiService
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import spray.http.StatusCodes
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest

import scala.concurrent.duration._

final class NotificationsApiServiceSpec extends FlatSpec with BeforeAndAfterAll with HttpService with ScalatestRouteTest with NotificationsApiService with Matchers with FireCloudRequestBuilding {
  def actorRefFactory = system

  implicit val routeTestTimeout = RouteTestTimeout(5.seconds)

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  private def trace(): Unit = {
    val get = Get(s"/api/notifications/workspace/${MockWorkspaceServer.mockValidWorkspace.namespace}/${MockWorkspaceServer.mockValidWorkspace.name}")
    val getWithHeader = get ~> dummyAuthHeaders
    val getWithHeaderSealed = getWithHeader ~> sealRoute(notificationsRoutes2)
    val getWithHeaderSealedCheck = getWithHeaderSealed ~>
      check {
        val resp = response
        println(s"responseEntity = ${response}")
        println(s"status = $status")
        assertResult(StatusCodes.OK, response.entity.asString) {
          status
        }
      }
  }

  "NotificationsApiService" should "get workspace notifications" in {
    trace()
    Get(s"/api/notifications/workspace/${MockWorkspaceServer.mockValidWorkspace.namespace}/${MockWorkspaceServer.mockValidWorkspace.name}") ~> dummyAuthHeaders ~>
      sealRoute(notificationsRoutes2) ~>
      check {
        assertResult(StatusCodes.OK, response.entity.asString) {
          status
        }
      }
  }

  it should "get general notifications" in {
    Get(s"/api/notifications/general") ~> dummyAuthHeaders ~>
      sealRoute(notificationsRoutes2) ~>
      check {
        assertResult(StatusCodes.OK, response.entity.asString) {
          status
        }
      }
  }
}
