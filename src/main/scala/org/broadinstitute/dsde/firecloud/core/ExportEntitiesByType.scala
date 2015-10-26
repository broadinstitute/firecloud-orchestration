package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.core.ExportEntitiesByType.ProcessEntities
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.{RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.utils.TSVFormatter
import spray.client.pipelining._
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.http.{HttpHeaders, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.util.{Failure, Success}

object ExportEntitiesByType {
  case class ProcessEntities(url: String, filename: String, entityType: String)
  def props(requestContext: RequestContext): Props = Props(new ExportEntitiesByTypeActor(requestContext))
}

class ExportEntitiesByTypeActor(requestContext: RequestContext) extends Actor with FireCloudRequestBuilding  {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  override def receive: Receive = {
    case ProcessEntities(encodedUrl: String, filename: String, entityType: String) =>
      log.debug("Processing entities for url: " + encodedUrl)
      val pipeline = authHeaders(requestContext) ~> sendReceive
      pipeline { Get(encodedUrl) } onComplete {
        case Success(response) if response.status == OK =>
          val entities = unmarshal[List[EntityWithType]].apply(response)
          log.debug("Processed entities: " + entities.toString)
          val data = TSVFormatter.makeTsvString(entities, entityType)
          context.parent ! RequestCompleteWithHeaders(
            (OK, data),
            HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> filename)),
            HttpHeaders.`Content-Type`(`text/plain`))
          context stop self
        case Success(response) if response.status != OK =>
          context.parent ! RequestComplete(response)
          context stop self
        case Failure(error) =>
          context.parent ! RequestComplete(StatusCodes.InternalServerError, error.getMessage)
          context stop self
      }
    case _ =>
      context.parent ! RequestComplete(StatusCodes.BadRequest)
      context stop self
  }

}


