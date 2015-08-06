package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.routing.{HttpService, Route}

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait MethodsService extends HttpService with FireCloudDirectives {

  val routes = localRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def localRoutes: Route =
    path("methods") {
      get { requestContext =>
        actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
          HttpClient.PerformExternalRequest(Get(FireCloudConfig.Methods.methodsListUrl))
      }
    } ~
    path("configurations") {
      get { requestContext =>
        actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
          HttpClient.PerformExternalRequest(Get(FireCloudConfig.Methods.configurationsListUrl))
      }
    }

}
