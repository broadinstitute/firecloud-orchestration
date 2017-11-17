package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.OntologyDAO
import org.broadinstitute.dsde.firecloud.service.OntologyService.AutocompleteOntology
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource
import spray.httpx.SprayJsonSupport
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object OntologyService {
  sealed trait OntologyServiceMessage
  case class AutocompleteOntology(term: String) extends OntologyServiceMessage

  def props(ontologyServiceConstructor: () => OntologyService): Props = {
    Props(ontologyServiceConstructor())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new OntologyService(app.ontologyDAO)
}

class OntologyService(val ontologyDAO: OntologyDAO)
                     (implicit protected val executionContext: ExecutionContext)
  extends Actor with SprayJsonSupport with LazyLogging {

  override def receive = {
    case AutocompleteOntology(term: String) => autocompleteOntology(term) pipeTo sender
  }

  def autocompleteOntology(term: String): Future[PerRequestMessage] = {
    Future(RequestComplete(ontologyDAO.autocomplete(term)))
  }


}
