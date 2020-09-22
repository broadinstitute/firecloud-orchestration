package org.broadinstitute.dsde.firecloud.service

import org.slf4j.LoggerFactory
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route

import scala.concurrent.ExecutionContext

trait HealthService extends FireCloudDirectives {

  implicit val executionContext: ExecutionContext
  lazy val log = LoggerFactory.getLogger(getClass)

  val healthServiceRoutes: Route = {
    path("health") { complete(OK) } ~
    path("error") { complete (ServiceUnavailable) }
  }

}
