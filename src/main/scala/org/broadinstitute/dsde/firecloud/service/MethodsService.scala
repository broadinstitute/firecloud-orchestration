package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.slf4j.LoggerFactory
import spray.routing.{HttpService, Route}

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait MethodsService extends HttpService with FireCloudDirectives {

  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    path("methods") {
      passthrough(FireCloudConfig.Agora.methodsListUrl, "get")
    } ~
    path("configurations") {
      passthrough(FireCloudConfig.Agora.configurationsListUrl, "get")
    }

}
