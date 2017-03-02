package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining._
import spray.http.Uri
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}


class HttpOntologyDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends OntologyDAO with RestJsonClient {

  override def search(term: String): Future[Option[List[TermResource]]] = {
    unAuthedRequest(Get(Uri(ontologySearchUrl).withQuery(("id", term)))) map { response =>
      if (response.status.isSuccess)
        response.entity.as[List[TermResource]] match {
          case Right(obj) => Some(obj)
          case Left(error) => None
        }
      else
        None
    }
  }
}
