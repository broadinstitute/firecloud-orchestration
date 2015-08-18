package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.contrib.pattern.Aggregator
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.{EntityWithType, ProcessUrl, TimedOut}
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.http.{HttpResponse, RequestProcessingException, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object GetEntitiesWithType {
  case object TimedOut
  case class ProcessUrl(url: String)
  case class AddEntities(entities: List[EntityWithType])
  case class EntityWithType(name: String, entityType: String, attributes: Option[Map[String, JsValue]])
  def props(requestContext: RequestContext): Props = Props(new GetEntitiesWithTypeActor(requestContext))
}

class GetEntitiesWithTypeActor(requestContext: RequestContext) extends Actor with Aggregator {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  expectOnce {
    case ProcessUrl(url: String) =>
      log.debug("Processing entity type map for url: " + url)
      val pipeline = addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive
      val entityTypesFuture: Future[HttpResponse] = pipeline { Get(url) }
      entityTypesFuture onComplete {
        case Success(response) =>
          val entityTypes: List[String] = unmarshal[List[String]].apply(response)
          new EntityAggregator(requestContext, url, entityTypes)
        case Failure(e) =>
          requestContext.failWith(new RequestProcessingException(StatusCodes.InternalServerError, e.getMessage))
          context stop self
      }
    case _ =>
      requestContext.complete(StatusCodes.BadRequest)
      context stop self
  }

  class EntityAggregator(requestContext: RequestContext, baseUrl: String, entityTypes: List[String]) {

    import context.dispatcher
    import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
    import spray.json.DefaultJsonProtocol._

    import collection.mutable.ArrayBuffer

    val values = ArrayBuffer.empty[EntityWithType]

    if (entityTypes.nonEmpty) {
      val pipeline = addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive
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
        case Failure(e) =>
          requestContext.failWith(new RequestProcessingException(StatusCodes.InternalServerError, e.getMessage))
          context stop self
      }
    }
    else collectEntities()

    context.system.scheduler.scheduleOnce(500.millisecond, self, TimedOut)
    expect {
      case TimedOut => collectEntities()
    }

    def collectEntities(): Unit = {
      requestContext.complete(OK, values.toList)
      context stop self
    }

  }

}

