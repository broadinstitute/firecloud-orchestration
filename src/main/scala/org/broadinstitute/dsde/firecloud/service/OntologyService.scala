package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{OntologyDAO, ResearchPurposeSupport}
import org.broadinstitute.dsde.firecloud.model.DataUse._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOntologyTermResource
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol._
import spray.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object OntologyService {

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new OntologyService(app.ontologyDAO, app.researchPurposeSupport)

}

class OntologyService(val ontologyDAO: OntologyDAO, val researchPurposeSupport: ResearchPurposeSupport)
                     (implicit protected val executionContext: ExecutionContext)
  extends DataUseRestrictionSupport with SprayJsonSupport with LazyLogging {

  def buildStructuredUseRestrictionAttribute(request: StructuredDataRequest): Future[PerRequestMessage] = {
    Future(RequestComplete(generateStructuredUseRestrictionAttribute(request, ontologyDAO)))
  }

  def autocompleteOntology(term: String): Future[PerRequestMessage] = {
    Future(RequestComplete(ontologyDAO.autocomplete(term)))
  }

  def buildResearchPurposeQuery(request: ResearchPurposeRequest): Future[PerRequestMessage] = {
    import spray.json._
    def addPrefix(name: String): String = request.prefix.getOrElse("") + name
    Future(RequestComplete(researchPurposeSupport.researchPurposeFilters(ResearchPurpose(request), addPrefix).toString.parseJson))
  }
}
