package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.http.{StatusCodes, Uri}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject

import scala.concurrent.{ExecutionContext, Future}


class HttpDuosDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends DuosDAO with RestJsonClient with LazyLogging {

  override def ontologySearch(term: String): Future[Option[List[TermResource]]] = {
    unAuthedRequest(Get(Uri(ontologySearchUrl).withQuery(("id", term)))) map { response =>
      response.entity.as[List[TermResource]] match {
        case Right(obj) => Some(obj)
        case Left(err) =>
          logger.warn(s"Error while retrieving ontology parents for id '$term': $err")
          None
      }
    }
  }

  override def orspIdSearch(userInfo: UserInfo, orspId: String): Future[Option[JsObject]] = {
    userAuthedRequest(Get(Uri(orspIdSearchUrl).withQuery(("name", orspId))))(userInfo) map { response =>
      response.status match {
        case StatusCodes.OK =>
          response.entity.as[JsObject] match {
            case Right(obj) => Some(obj)
            case Left(err) =>
              logger.warn(s"Error while retrieving consent for orsp id '$orspId': $err")
              None
          }
        case _ =>
          logger.error(response.toString)
          None
      }
    }
  }

}
