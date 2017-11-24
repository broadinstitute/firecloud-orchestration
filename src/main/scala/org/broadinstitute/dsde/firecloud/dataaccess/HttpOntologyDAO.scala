package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.{ActorRef, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.SubsystemStatus
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException}
import spray.can.Http
import spray.http.{HttpResponse, Uri}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@deprecated("use ElasticSearchOntologyDAO instead", "2017-11-17")
class HttpOntologyDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends OntologyDAO with RestJsonClient with LazyLogging {

  private val ontologyUri = Uri(FireCloudConfig.Duos.baseOntologyUrl)

  private val (ontologyPort, sslEncryption) = (ontologyUri.authority.port, ontologyUri.scheme) match {
    case (0, "https") => (443, true)
    case (0, "http") => (80, false)
    case (port:Int, "https") => (port, true)
    case (port:Int, "http") => (port, false)
    case _ => throw new FireCloudException(s"Could not parse ontologyUri: ${ontologyUri.toString}")
  }

  private val ontologyHostSetup = Http.HostConnectorSetup(ontologyUri.authority.host.address, ontologyPort, sslEncryption)

  override def search(term: String): List[TermResource] = List.empty[TermResource]
  override def autocomplete(term: String) = List.empty[TermResource]

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

  override def status: Future[SubsystemStatus] = {
    getStatusFromDropwizardChecks(unAuthedRequest(Get(ontologyUri.withPath(Uri.Path("/status")))))
  }


}
