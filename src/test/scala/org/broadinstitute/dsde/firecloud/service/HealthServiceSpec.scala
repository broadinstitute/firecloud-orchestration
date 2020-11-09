package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import scala.concurrent.ExecutionContext

class HealthServiceSpec extends ServiceSpec with HealthService {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  "HealthService" - {

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
