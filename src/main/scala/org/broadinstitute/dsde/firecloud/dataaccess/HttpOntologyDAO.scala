package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining._
import spray.http.Uri
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}


class HttpOntologyDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends OntologyDAO with RestJsonClient with LazyLogging {

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

}
