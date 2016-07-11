package org.broadinstitute.dsde.firecloud.service

import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.routing.{HttpService, Route}

trait HealthService extends HttpService with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route = {
    path("health") { complete(OK) } ~
    path("error") { complete (ServiceUnavailable) }
  }

}
