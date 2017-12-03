package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems._

import scala.concurrent.Future

/**
  * Created by davidan on 9/23/16.
  */

case class Application(agoraDAO: AgoraDAO,
                       googleServicesDAO: GoogleServicesDAO,
                       ontologyDAO: OntologyDAO,
                       consentDAO: ConsentDAO,
                       rawlsDAO: RawlsDAO,
                       samDAO: SamDAO,
                       searchDAO: SearchDAO,
                       thurloeDAO: ThurloeDAO,
                       trialDAO: TrialDAO) {

  def healthMonitorChecks: Map[Subsystem, Future[SubsystemStatus]] = Map(
    Agora -> agoraDAO.status,
    Consent -> consentDAO.status,
    GoogleBuckets -> googleServicesDAO.status,
    LibraryIndex -> searchDAO.status,
    OntologyIndex -> ontologyDAO.status,
    Rawls -> rawlsDAO.status,
    Sam -> samDAO.status,
    Thurloe -> thurloeDAO.status
    // TODO: add free-trial index as a monitorable healthcheck; requires updates to workbench-libs
  )

}
