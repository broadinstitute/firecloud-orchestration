package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.routing.{HttpService, Route}

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait MethodsService extends HttpService with PerRequestCreator with FireCloudDirectives {

  val routes = localRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def localRoutes: Route =
    path("methods") {
      get { requestContext =>
        val extReq = Get(FireCloudConfig.Agora.methodsListUrl)
        externalHttpPerRequest(requestContext, extReq)
      }
    } ~
    path("configurations") {
      get { requestContext =>
        val extReq = Get(FireCloudConfig.Agora.configurationsListUrl)
        externalHttpPerRequest(requestContext, extReq)
      }
    }

}
