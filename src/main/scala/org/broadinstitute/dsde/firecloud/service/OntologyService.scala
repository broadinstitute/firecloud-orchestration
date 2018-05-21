package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{OntologyDAO, ResearchPurposeSupport}
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource
import org.broadinstitute.dsde.firecloud.service.OntologyService.{AutocompleteOntology, DataUseLimitation, ResearchPurposeQuery}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object OntologyService {
  sealed trait OntologyServiceMessage
  case class AutocompleteOntology(term: String) extends OntologyServiceMessage
  case class ResearchPurposeQuery(researchPurposeRequest: ResearchPurposeRequest) extends OntologyServiceMessage
  case class DataUseLimitation(dataUseLimitation: StructuredDataRequest) extends OntologyServiceMessage

  def props(ontologyServiceConstructor: () => OntologyService): Props = {
    Props(ontologyServiceConstructor())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new OntologyService(app.ontologyDAO, app.researchPurposeSupport)
}

class OntologyService(val ontologyDAO: OntologyDAO, val researchPurposeSupport: ResearchPurposeSupport)
                     (implicit protected val executionContext: ExecutionContext)
  extends Actor with DataUseRestrictionSupport with SprayJsonSupport with LazyLogging {

  override def receive = {
    case AutocompleteOntology(term: String) => autocompleteOntology(term) pipeTo sender
    case ResearchPurposeQuery(researchPurposeRequest: ResearchPurposeRequest) => buildResearchPurposeQuery(researchPurposeRequest) pipeTo sender
    case DataUseLimitation(dataUseLimitation: StructuredDataRequest) => buildStructuredUseRestrictionAttribute(dataUseLimitation) pipeTo sender
  }

  def buildStructuredUseRestrictionAttribute(request: StructuredDataRequest): Future[Map[String, JsValue]] = {
    Future(generateStructuredUseRestrictionAttribute(request, ontologyDAO))
  }

  def autocompleteOntology(term: String): Future[PerRequestMessage] = {
    Future(RequestComplete(ontologyDAO.autocomplete(term)))
  }

  def buildResearchPurposeQuery(request: ResearchPurposeRequest): Future[PerRequestMessage] = {
    def addPrefix(name: String): String = request.prefix.getOrElse("") + name
    Future(RequestComplete(researchPurposeSupport.researchPurposeFilters(ResearchPurpose(request), addPrefix).toString))
  }
}
