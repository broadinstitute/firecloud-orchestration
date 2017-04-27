package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.SubsystemStatus

import scala.concurrent.Future

/**
  * Created by anichols on 4/21/17.
  */
trait ReportsSubsystemStatus {

  def status: Future[SubsystemStatus]
}
