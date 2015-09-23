package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.contrib.pattern.Aggregator
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.{EntityWithType, ProcessUrl}
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{HttpResponse, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object GetEntitiesWithType {
  case class ProcessUrl(url: String)
  case class EntityWithType(name: String, entityType: String, attributes: Option[Map[String, JsObject]])
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
        case Success(response) =>
          val entityTypes: List[String] = unmarshal[List[String]].apply(response)
          new EntityAggregator(requestContext, url, entityTypes)
        case Failure(e) =>
          context.parent ! RequestComplete(StatusCodes.InternalServerError, e.getMessage)
          context stop self
      }
    case _ =>
      context.parent ! RequestComplete(StatusCodes.BadRequest)
      context stop self
  }

  class EntityAggregator(requestContext: RequestContext, baseUrl: String, entityTypes: List[String]) {

    import context.dispatcher
    import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

    import collection.mutable.ArrayBuffer

    val values = ArrayBuffer.empty[EntityWithType]

    if (entityTypes.nonEmpty) {
      val pipeline = authHeaders(requestContext) ~> sendReceive
      val entityUrls: List[String] = entityTypes.map(s => s"$baseUrl/$s")
      val entityFutures: List[Future[HttpResponse]] = entityUrls map { entitiesUrl => pipeline { Get(entitiesUrl) } }
      val f: Future[List[HttpResponse]] = Future sequence entityFutures
      f onComplete {
        case Success(responses) =>
          responses flatMap {
            response =>
              val entities = unmarshal[List[EntityWithType]].apply(response)
              values ++= entities
          }
          collectEntities()
        case Failure(e) =>
          context.parent ! RequestComplete(StatusCodes.InternalServerError, e.getMessage)
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

