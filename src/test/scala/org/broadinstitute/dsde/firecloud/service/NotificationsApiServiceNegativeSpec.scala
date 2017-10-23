package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.webservice.NotificationsApiService
import spray.http.HttpMethods.GET
import spray.http.StatusCodes.{InternalServerError, MethodNotAllowed}
import spray.http.{HttpMethod, StatusCode}

/** We don't create a mock server so we can differentiate between [[MethodNotAllowed]] and [[InternalServerError]] */
final class NotificationsApiServiceNegativeSpec extends ServiceSpec with NotificationsApiService {

  def actorRefFactory = system

  "NotificationsApiService" - {
    "GET request should be passed through (and hit 500 due to the absence of a mock server)" in {
      doAssert(GET, "/api/notifications/general", InternalServerError)
    }

    "non-GET requests should not be passed through" in {
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
