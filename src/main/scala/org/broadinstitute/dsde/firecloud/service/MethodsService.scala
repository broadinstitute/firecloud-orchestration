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
  val remoteMethodsPath = FireCloudConfig.Agora.authPrefix + "/methods"
  val remoteMethodsUrl = FireCloudConfig.Agora.baseUrl + remoteMethodsPath
  val remoteConfigurationsPath = FireCloudConfig.Agora.authPrefix + "/configurations"
  val remoteConfigurationsUrl = FireCloudConfig.Agora.baseUrl + remoteConfigurationsPath
}

trait MethodsService extends HttpService with FireCloudDirectives {

  lazy val log = LoggerFactory.getLogger(getClass)

  val localMethodsPath = "methods"
  val localConfigsPath = "configurations"

  val routes: Route =
    passthroughAllPaths(localMethodsPath, MethodsService.remoteMethodsUrl) ~
    passthroughAllPaths(localConfigsPath, MethodsService.remoteConfigurationsUrl)

}
