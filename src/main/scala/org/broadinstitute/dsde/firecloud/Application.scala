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
                       researchPurposeSupport: ResearchPurposeSupport,
                       thurloeDAO: ThurloeDAO,
                       logitDAO: LogitDAO,
                       shareLogDAO: ShareLogDAO)
