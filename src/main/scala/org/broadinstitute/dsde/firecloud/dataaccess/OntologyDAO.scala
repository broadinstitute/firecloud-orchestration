package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.Ontology.TermResource
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import org.broadinstitute.dsde.workbench.util.health.Subsystems
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem

import scala.concurrent.{ExecutionContext, Future}

object OntologyDAO {
  lazy val serviceName = Subsystems.OntologyIndex
}

trait OntologyDAO extends ReportsSubsystemStatus {

  lazy val ontologySearchUrl = FireCloudConfig.Duos.baseOntologyUrl + "/search"

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(OntologyDAO.serviceName.value)

  def search(term: String): List[TermResource]

  def autocomplete(term: String): List[TermResource]

  override def serviceName:Subsystem = OntologyDAO.serviceName

}
