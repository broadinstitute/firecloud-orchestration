package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SearchDAO}

/**
  * Created by davidan on 9/23/16.
  */
class Application (val rawlsDAO: RawlsDAO,
                   val searchDAO: SearchDAO) {

}
