package org.broadinstitute.dsde.firecloud.dataaccess

import java.time.Instant
import org.broadinstitute.dsde.firecloud.integrationtest.ElasticSearchShareLogDAOSpecFixtures
import org.broadinstitute.dsde.firecloud.model.ShareLog.Share
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockShareLogDAO extends ShareLogDAO {

  val exception = "unit test exception: override in a class specific to test"

  override def logShare(userId: String, sharee: String, shareType: String): Share = throw new Exception("unit test exception: override in a class specific to test")

  override def getShare(share: Share): Share = throw new Exception("unit test exception: override in a class specific to test")

  override def getShares(userId: String, shareType: Option[String] = None): Seq[Share] = throw new Exception("unit test exception: override in a class specific to test")

  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))
}

class ShareLogApiServiceSpecShareLogDAO extends MockShareLogDAO {

  override def getShares(userId: String, shareType: Option[String]): Seq[Share] = {
    ElasticSearchShareLogDAOSpecFixtures.fixtureShares
  }
}

class WorkspaceApiServiceSpecShareLogDAO extends MockShareLogDAO {
  override def logShare(userId: String, sharee: String, shareType: String): Share = {
    Share(userId, sharee, shareType, Some(Instant.now))
  }
}