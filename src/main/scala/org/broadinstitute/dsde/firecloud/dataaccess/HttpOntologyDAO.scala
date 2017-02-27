package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import spray.client.pipelining._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}


class HttpOntologyDAO(implicit val system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends OntologyDAO with RestJsonClient {

  override def search(term: String)(implicit userInfo: UserInfo): Future[List[TermResource]] = {
    requestToObject[List[TermResource]](Get(ontologySearchUrl))
  }
}
