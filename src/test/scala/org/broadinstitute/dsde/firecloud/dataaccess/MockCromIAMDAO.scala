package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockCromIAMDAO extends CromIAMDAO {
  override def submit(wdlUrl: String, inputs: String, options: Option[String])(implicit userToken: WithAccessToken): Future[String] = Future.successful("123-456-789")

  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))
}
