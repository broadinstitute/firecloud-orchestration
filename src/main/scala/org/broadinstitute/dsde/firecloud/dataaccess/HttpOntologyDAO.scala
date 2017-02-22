package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.Ontology.SearchResponse
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{HttpResponse, OAuth2BearerToken}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}


class HttpOntologyDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends OntologyDAO with RestJsonClient {

  override def search(term: String): Future[List[SearchResponse]] = {
    requestToObject[List[SearchResponse]](Get(ontologySearchUrl))
  }
}
