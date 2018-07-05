package org.broadinstitute.dsde.firecloud.dataaccess
import java.time.Instant

import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

class MockShareLogDAO extends ShareLogDAO {

  val exception = "unit test exception: override in a class specific to test"

  override def logShare(userId: String, sharee: String, shareType: String): Share = throw new Exception("unit test exception: override in a class specific to test")

  override def getShare(id: String): Share = throw new Exception("unit test exception: override in a class specific to test")

  override def getShares(userId: String, shareType: Option[String] = None): Seq[Share] = throw new Exception("unit test exception: override in a class specific to test")

  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))
}
