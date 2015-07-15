package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import com.wordnik.swagger.annotations.{Api, ApiOperation}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.routing.{HttpService, Route}

import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

@Api(value = "/methods", description = "Methods Service", produces = "application/json, text/plain")
trait MethodsService extends HttpService with FireCloudDirectives {

  private final val ApiPrefix = "methods"
  val routes = listMethodsRoute
  lazy val log = LoggerFactory.getLogger(getClass)

  @ApiOperation(
    value = "list methods",
    nickname = "listMethods",
    httpMethod = "GET",
    notes = "The response is forwarded unmodified from the methods repository.")
  def listMethodsRoute: Route =
    path(ApiPrefix) {
      get { requestContext =>
        actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
          HttpClient.PerformExternalRequest(Get(FireCloudConfig.Methods.methodsListUrl))
      }
    }
}
