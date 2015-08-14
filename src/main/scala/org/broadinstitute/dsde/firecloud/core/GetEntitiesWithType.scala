package org.broadinstitute.dsde.firecloud.core

import akka.actor.{Actor, Props}
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.{AddEntities, EntityWithType, ProcessUrl}
import spray.client.pipelining._
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.http.{HttpRequest, HttpResponse, RequestProcessingException, StatusCodes}
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

object GetEntitiesWithType {
  case class ProcessUrl(url: String)
  case class AddEntities(entities: List[EntityWithType])
  case class EntityWithType(name: String, entityType: String)
  def props(requestContext: RequestContext): Props = Props(new GetEntitiesWithTypeActor(requestContext))
}

class GetEntitiesWithTypeActor(requestContext: RequestContext) extends Actor {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  var results = List.empty[EntityWithType]

  override def receive: Receive = {
    case ProcessUrl(url: String) => processUrl(url)
    case AddEntities(entities: List[EntityWithType]) => results = results ::: entities
  }

  def processUrl(url: String): Unit = {
    log.debug("Processing entity type map for url: " + url)
    val pipeline: HttpRequest => Future[HttpResponse] = addHeader(Cookie(requestContext.request.cookies)) ~> sendReceive
    val entityTypesFuture: Future[HttpResponse] = pipeline { Get(url) }
    val ffewt: Future[Future[List[EntityWithType]]] = entityTypesFuture map {
      response =>
        val entityTypes: List[String] = unmarshal[List[String]].apply(response)
        val entityUrls: List[String] = entityTypes.map(s => s"$url/$s")
        val entityFutures: List[Future[HttpResponse]] = entityUrls map { url => pipeline { Get(url) } }
        Future.sequence(entityFutures) map {
          responses =>
            responses flatMap {
              response =>
                val entities = unmarshal[List[EntityWithType]].apply(response)
                log.debug("Processed entities: " + entities)
                self ! AddEntities(entities)
                entities
            }
        }
    }
    // Unwrap the nested futures:
    ffewt onComplete {
      case Success(fewt) =>
        fewt onComplete {
          case Success(_) =>
            requestContext.complete(OK, results)
            context stop self
          case Failure(e) =>
            log.error(e.getMessage)
            requestContext.failWith(
              new RequestProcessingException(StatusCodes.InternalServerError, e.getMessage)
            )
            context stop self
        }
      case Failure(e) =>
        log.error(e.getMessage)
        requestContext.failWith(
          new RequestProcessingException(StatusCodes.InternalServerError, e.getMessage)
        )
        context stop self
    }

  }

}

