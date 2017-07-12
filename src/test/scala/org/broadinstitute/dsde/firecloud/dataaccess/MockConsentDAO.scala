package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.SubsystemStatus
import scala.concurrent.Future

class MockConsentDAO extends ConsentDAO {

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(true))

}
