package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes.{OK, ServiceUnavailable}
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

trait HealthApiService extends FireCloudDirectives {

  implicit val executionContext: ExecutionContext
  lazy val log = LoggerFactory.getLogger(getClass)

  val healthServiceRoutes: Route = {
    path("health") {
      complete(OK)
    } ~
      path("error") {
        complete(ServiceUnavailable)
      }
  }

}
