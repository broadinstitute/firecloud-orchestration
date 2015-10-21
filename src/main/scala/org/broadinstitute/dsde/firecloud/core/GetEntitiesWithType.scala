package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.contrib.pattern.Aggregator
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.{EntityWithType, ProcessUrl}
import org.broadinstitute.dsde.firecloud.model.{ErrorReport, RequestCompleteWithErrorReport}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectiveUtils, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object GetEntitiesWithType {
  case class ProcessUrl(url: String)
  case class EntityWithType(name: String, entityType: String, attributes: Option[Map[String, JsValue]])
  def props(requestContext: RequestContext): Props = Props(new GetEntitiesWithTypeActor(requestContext))
}

class GetEntitiesWithTypeActor(requestContext: RequestContext) extends Actor with Aggregator with FireCloudRequestBuilding {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  expectOnce {
    case ProcessUrl(url: String) =>
      log.debug("Processing entity type map for url: " + url)
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val entityTypesFuture: Future[HttpResponse] = pipeline { Get(url) }
      entityTypesFuture onComplete {
        case Success(response) if response.status == OK =>
          val entityTypes: List[String] = unmarshal[List[String]].apply(response)
          new EntityAggregator(requestContext, url, entityTypes)
        case Success(response) =>
          context.parent ! RequestComplete(response)
          context stop self
        case _ =>
          context.parent ! RequestCompleteWithErrorReport(InternalServerError, "Request failed for URL " + url)
          context stop self
      }
    case _ =>
      context.parent ! RequestCompleteWithErrorReport(BadRequest, "Invalid message received by GetEntitiesWithTypeActor")
      context stop self
  }

  class EntityAggregator(requestContext: RequestContext, baseUrl: String, entityTypes: List[String]) {

    import context.dispatcher
    import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

    import collection.mutable.ArrayBuffer

    val values = ArrayBuffer.empty[EntityWithType]

    if (entityTypes.nonEmpty) {
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val entityUrls: List[String] = entityTypes.map(s => FireCloudDirectiveUtils.encodeUri(s"$baseUrl/$s"))
      val entityFutures: List[Future[HttpResponse]] = entityUrls map { entitiesUrl => pipeline { Get(entitiesUrl) } }
      val f: Future[List[HttpResponse]] = Future sequence entityFutures
      f onComplete {
        case Success(responses) if responses.forall(_.status == OK) =>
          responses.flatMap {
            response =>
              val entities = unmarshal[List[EntityWithType]].apply(response)
              values ++= entities
          }
          collectEntities()
        case Success(responses) =>
          val errors = responses.filterNot(_.status == OK) map { e => (e, ErrorReport.tryUnmarshal(e) ) }
          val errorReports = errors collect { case (_, Success(report)) => report }
          val missingReports = errors collect { case (originalError, Failure(_)) => originalError }

          val errorMessage = {
            val baseMessage = "%d failures out of %d attempts retrieving entityUrls.  Errors: %s".format(entityTypes.size, errors.size, errors mkString ",")
            if (missingReports.isEmpty) baseMessage
            else {
              val supplementalErrorMessage = "Additionally, %d of these failures did not provide error reports: %s".format(missingReports.size, missingReports mkString ",")
              baseMessage + "\n" + supplementalErrorMessage
            }
          }

          context.parent ! RequestCompleteWithErrorReport(InternalServerError, errorMessage, errorReports)
          context stop self
        case _ =>
          context.parent ! RequestCompleteWithErrorReport(InternalServerError, "Failure retrieving entityUrls: [%s]".format(entityUrls mkString ","))
          context stop self
      }
    }
    else collectEntities()

    def collectEntities(): Unit = {
      context.parent ! RequestComplete(OK, values.toList)
      context stop self
    }

  }

}

