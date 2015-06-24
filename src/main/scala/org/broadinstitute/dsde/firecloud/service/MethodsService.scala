package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, _}
import com.wordnik.swagger.annotations.{Api, _}
import org.broadinstitute.dsde.firecloud.MethodsClient
import org.broadinstitute.dsde.firecloud.model.MethodEntity
import org.slf4j.LoggerFactory
import spray.routing.{HttpService, Route}

class MethodsServiceActor extends Actor with MethodsService {
  def actorRefFactory = context
  def receive = runRoute(listRoute)
}

@Api(value = "/methods", description = "Methods Service", produces = "application/json")
trait MethodsService extends HttpService with FireCloudDirectives {

  private final val ApiPrefix = "methods"

  val listRoute = listMethodsRoute

  lazy val log = LoggerFactory.getLogger(getClass)

  @ApiOperation(
    value = "list methods",
    nickname = "listMethods",
    httpMethod = "GET",
    response = classOf[MethodEntity],
    responseContainer = "List",
    notes = "response is a list of methods from the methods repository")
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Successful"),
    new ApiResponse(code = 500, message = "Internal Error")))
  def listMethodsRoute: Route =
    path(ApiPrefix) {
      get {
        respondWithJSON { requestContext =>
          val methodsClient = actorRefFactory.actorOf(Props(new MethodsClient(requestContext)))
          methodsClient ! MethodsClient.MethodsListRequest
        }
      }
    }

}
