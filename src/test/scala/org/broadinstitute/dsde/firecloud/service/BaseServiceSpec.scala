package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO

class BaseServiceSpec extends ServiceSpec {

  val agoraDAO:AgoraDAO = new MockAgoraDAO
  val rawlsDAO:RawlsDAO = new MockRawlsDAO
  val searchDAO:SearchDAO = new MockSearchDAO
  val thurloeDAO:ThurloeDAO = new MockThurloeDAO
  val googleServicesDAO:GoogleServicesDAO = new MockGoogleServicesDAO
  val app:Application = new Application(agoraDAO, rawlsDAO, searchDAO, thurloeDAO, googleServicesDAO)

}
