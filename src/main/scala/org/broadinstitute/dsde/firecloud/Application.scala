package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, RawlsDAO, SearchDAO}

/**
  * Created by davidan on 9/23/16.
  */
class Application (val agoraDAO: AgoraDAO,
                   val rawlsDAO: RawlsDAO,
                   val searchDAO: SearchDAO) {

}
