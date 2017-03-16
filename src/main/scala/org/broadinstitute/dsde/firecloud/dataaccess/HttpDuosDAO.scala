package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.DUOS.Consent
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining._
import spray.http.Uri
import spray.http.{StatusCodes, Uri}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.UserInfo
import spray.json.JsObject
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}


class HttpDuosDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends DuosDAO with RestJsonClient with LazyLogging {

  override def search(term: String): Future[Option[List[TermResource]]] = {
    searchBlocking(term)
  }

  @deprecated("uses blocking I/O, boo!", "2017-03-15")
  private def searchBlocking(term: String): Future[Option[List[TermResource]]] = {
    val targetUri = Uri(ontologySearchUrl).withQuery(("id", term))
    logger.info(s"HttpOntologyDAO querying ${term} ...")
    val response = Await.result(unAuthedRequest(Get(targetUri)), Duration(30, "seconds"))
    logger.info(s"HttpOntologyDAO querying ${term}: ${response.status.defaultMessage}")
    response.entity.as[List[TermResource]] match {
      case Right(obj) => Future(Some(obj))
      case Left(err) =>
        logger.warn(s"Error while retrieving ontology parents for id '$term': $err")
        Future(None)
    }
  }

  private def searchAsync(term: String): Future[Option[List[TermResource]]] = {
    val targetUri = Uri(ontologySearchUrl).withQuery(("id", term))
    logger.info(s"HttpOntologyDAO querying ${term} ...")
    unAuthedRequest(Get(targetUri)) map { response =>
      logger.info(s"HttpOntologyDAO querying ${term}: ${response.status.defaultMessage}")
      response.entity.as[List[TermResource]] match {
        case Right(obj) => Some(obj)
        case Left(err) =>
          logger.warn(s"Error while retrieving ontology parents for id '$term': $err")
          None
      }
    }
  }

  override def orspIdSearch(userInfo: UserInfo, orspId: String): Future[Option[Consent]] = {
    userAuthedRequest(Get(Uri(orspIdSearchUrl).withQuery(("name", orspId))))(userInfo) map { response =>
      response.status match {
        case StatusCodes.OK =>
          response.entity.as[Consent] match {
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
