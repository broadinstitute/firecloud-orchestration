package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.slf4j.LoggerFactory
import spray.routing.{HttpService, Route}

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

object MethodsService {
  val remoteMethodsUrl = FireCloudConfig.Agora.authUrl + "/methods"
  val remoteConfigurationsUrl = FireCloudConfig.Agora.authUrl + "/configurations"
}

trait MethodsService extends HttpService with FireCloudDirectives {

  lazy val log = LoggerFactory.getLogger(getClass)

  val localMethodsPath = "methods"
  val localConfigsPath = "configurations"

  val routes: Route =
    passthroughAllPaths(localMethodsPath, MethodsService.remoteMethodsUrl) ~
    passthroughAllPaths(localConfigsPath, MethodsService.remoteConfigurationsUrl)

}
