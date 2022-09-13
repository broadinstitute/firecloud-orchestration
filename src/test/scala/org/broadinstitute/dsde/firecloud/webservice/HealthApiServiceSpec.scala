package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.service.ServiceSpec

import scala.concurrent.ExecutionContext

class HealthApiServiceSpec extends ServiceSpec with HealthApiService {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  "HealthApiService" - {
    "when GET-ting the health service endpoint" - {
      "OK response is returned" in {
        Get("/health") ~> sealRoute(healthServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
      "Service Unavailable response is returned" in {
        Get("/error") ~> sealRoute(healthServiceRoutes) ~> check {
          status should equal(ServiceUnavailable)
        }
      }
    }
  }

}
