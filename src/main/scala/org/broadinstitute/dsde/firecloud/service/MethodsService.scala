package org.broadinstitute.dsde.firecloud.service

import javax.ws.rs.Path

import akka.actor.{Actor, Props}
import com.wordnik.swagger.annotations.{ApiResponse, ApiResponses, Api, ApiOperation}
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{Configuration, Method}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.routing.{HttpService, Route}

import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait MethodsService extends HttpService with FireCloudDirectives {

  val routes = listMethodsRoute ~ listConfigurationsRoute
  lazy val log = LoggerFactory.getLogger(getClass)

  @Path("/methods")
  @Api(value = "/methods",
    description = "Methods Repository Service",
    produces = "application/json")
  @ApiOperation(
    value = "list methods",
    nickname = "list methods",
    httpMethod = "GET",
    response = classOf[Method],
    responseContainer = "List",
    notes = "response is a list of methods from the methods repository")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Successful Request"),
    new ApiResponse(code = 500, message = "Internal Error")))
  def listMethodsRoute: Route =
    path("methods") {
      get { requestContext =>
        actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
          HttpClient.PerformExternalRequest(Get(FireCloudConfig.Methods.methodsListUrl))
      }
    }

  @Path("/configurations")
  @Api(value = "/configurations",
    description = "Methods Repository Service",
    produces = "application/json")
  @ApiOperation(
    value = "list configurations",
    nickname = "list configurations",
    httpMethod = "GET",
    response = classOf[Configuration],
    responseContainer = "List",
    notes = "response is a list of configurations from the methods repository")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Successful Request"),
    new ApiResponse(code = 500, message = "Internal Error")))
  def listConfigurationsRoute: Route =
    path("configurations") {
      get { requestContext =>
        actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
          HttpClient.PerformExternalRequest(Get(FireCloudConfig.Methods.configurationsListUrl))
      }
    }

}
