package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.{ActorRef, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.can.Http
import spray.client.pipelining._
import spray.http.Uri
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


class HttpOntologyDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends OntologyDAO with RestJsonClient with LazyLogging {

  private val ontologyUri = Uri(FireCloudConfig.Duos.baseOntologyUrl)
  private val ontologyHostSetup = Http.HostConnectorSetup(ontologyUri.authority.host.address,
    ontologyUri.authority.port, ontologyUri.scheme.equalsIgnoreCase("https"))

  override def search(term: String): Future[Option[List[TermResource]]] = {
    searchAsync(term)
  }

  private def searchAsync(term: String): Future[Option[List[TermResource]]] = {
    getHostConnector flatMap { hostConnector =>
      val targetUri = Uri(ontologySearchUrl).withQuery(("id", term))
      logger.debug(s"HttpOntologyDAO querying ${term} ...")
      unAuthedRequest(Get(targetUri), connector = Some(hostConnector)) map { response =>
        logger.debug(s"HttpOntologyDAO querying ${term}: ${response.status.defaultMessage}")
        response.entity.as[List[TermResource]] match {
          case Right(obj) => Some(obj)
          case Left(err) =>
            logger.warn(s"Error while retrieving ontology parents for id '$term': $err")
            None
        }
      }
    }
  }

  private def getHostConnector: Future[ActorRef] = {
    implicit val timeout:Timeout = 60.seconds // timeout to get the host connector reference
    for (Http.HostConnectorInfo(connector, _) <- IO(Http) ? ontologyHostSetup) yield connector
  }
}
