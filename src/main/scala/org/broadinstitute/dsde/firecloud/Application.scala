package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess._

/**
  * Created by davidan on 9/23/16.
  */

case class Application(agoraDAO: AgoraDAO,
                       googleServicesDAO: GoogleServicesDAO,
                       ontologyDAO: OntologyDAO,
                       rawlsDAO: RawlsDAO,
                       samDAO: SamDAO,
                       searchDAO: SearchDAO,
                       researchPurposeSupport: ResearchPurposeSupport,
                       thurloeDAO: ThurloeDAO,
                       shareLogDAO: ShareLogDAO,
                       importServiceDAO: ImportServiceDAO,
                       shibbolethDAO: ShibbolethDAO,
                       cwdsDAO: CwdsDAO)
