package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.scalatest.BeforeAndAfter

class BaseServiceSpec extends ServiceSpec with BeforeAndAfter {

  val agoraDao:MockAgoraDAO = new MockAgoraDAO
  val googleServicesDao:MockGoogleServicesDAO = new MockGoogleServicesDAO
  val rawlsDao:MockRawlsDAO = new MockRawlsDAO
  val searchDao:MockSearchDAO = new MockSearchDAO
  val thurloeDao:MockThurloeDAO = new MockThurloeDAO
  val ontologyDao:MockOntologyDAO = new MockOntologyDAO

  val app:Application =
    new Application(agoraDao, rawlsDao, searchDao, thurloeDao, googleServicesDao, ontologyDao)

  // Don't copy this pattern. See GAWB-1477.
  def reset() = {
    thurloeDao.reset()
  }

  before {
    reset()
  }
}
