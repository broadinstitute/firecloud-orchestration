package org.broadinstitute.dsde.firecloud

import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.MethodsClient.MethodsListRequest
import org.broadinstitute.dsde.firecloud.model.MethodEntity
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.ServiceUtils
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

object MethodsClient {
  
  case class MethodsListRequest()

  def props(requestContext: RequestContext): Props = Props(new MethodsClient(requestContext))

}

class MethodsClient(requestContext: RequestContext) extends Actor {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {
    case MethodsListRequest => listMethods(sender())
  }

  def listMethods(senderRef: ActorRef): Unit = {
    log.info("Find all methods in the methods repository")
    def completeSuccessfully(requestContext: RequestContext, response: HttpResponse): Unit = {
      requestContext.complete(unmarshal[List[MethodEntity]].apply(response))
    }
    ServiceUtils.completeFromExternalRequest(ServiceUtils.ExternalRequestParams(
      log, context, FireCloudConfig.Methods.methodsListUrl, requestContext, completeSuccessfully
    ))
  }
}
