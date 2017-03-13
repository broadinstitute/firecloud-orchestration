package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess._

/**
  * Created by davidan on 9/23/16.
  */

class Application (val agoraDAO: AgoraDAO,
                   val googleServicesDAO: GoogleServicesDAO,
                   val ontologyDAO: DuosDAO,
                   val rawlsDAO: RawlsDAO,
                   val searchDAO: SearchDAO,
                   val thurloeDAO: ThurloeDAO) {

}
