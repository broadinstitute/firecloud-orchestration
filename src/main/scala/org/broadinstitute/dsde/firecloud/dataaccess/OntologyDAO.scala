package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.{ExecutionContext, Future}

object OntologyDAO {
  lazy val serviceName = "Ontology"
}

trait OntologyDAO extends ReportsSubsystemStatus {

  lazy val ontologySearchUrl = FireCloudConfig.Duos.baseOntologyUrl + "/search"

  implicit val errorReportSource = ErrorReportSource(OntologyDAO.serviceName)

  def search(term: String): Future[Option[List[TermResource]]]

  override def serviceName:String = OntologyDAO.serviceName

}
