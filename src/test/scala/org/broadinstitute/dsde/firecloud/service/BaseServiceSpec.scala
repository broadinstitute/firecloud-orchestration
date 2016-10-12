package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._

class BaseServiceSpec extends ServiceSpec {

  val agoraDAO:AgoraDAO = new MockAgoraDAO
  val rawlsDAO:RawlsDAO = new MockRawlsDAO
  val searchDAO:SearchDAO = new MockSearchDAO
  val app:Application = new Application(agoraDAO, rawlsDAO, searchDAO)

}
