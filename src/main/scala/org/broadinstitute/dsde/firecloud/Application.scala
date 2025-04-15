package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess._

/**
  * Created by davidan on 9/23/16.
  */

case class Application(agoraDAO: AgoraDAO,
                       googleServicesDAO: GoogleServicesDAO,
                       rawlsDAO: RawlsDAO,
                       samDAO: SamDAO,
                       thurloeDAO: ThurloeDAO,
                       shibbolethDAO: ShibbolethDAO,
                       cwdsDAO: CwdsDAO,
                       ecmDAO: ExternalCredsDAO
)
