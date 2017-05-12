package org.broadinstitute.dsde.firecloud.service

import spray.http.StatusCodes._
import spray.routing.HttpService


class HealthServiceSpec extends ServiceSpec with HttpService with HealthService {

  def actorRefFactory = system

  "HealthService" - {

    "when GET-ting the health service endpoint" - {
      "OK response is returned" in {
        Get("/health") ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
      "Service Unavailable response is returned" in {
        Get("/error") ~> sealRoute(routes) ~> check {
          status should equal(ServiceUnavailable)
        }
      }
    }
  }

}
