package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.integrationtest.ElasticSearchShareLogDAOSpecFixtures
import org.broadinstitute.dsde.firecloud.model.ShareLog.{Share, ShareType}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockShareLogDAO extends ShareLogDAO {

  private val errorMessage = "unit test exception: override in a class specific to test"

  override def logShare(userId: String, sharee: String, shareType: ShareType.Value): Share = throw new Exception(errorMessage)

  override def logShares(userId: String, sharees: Seq[String], shareType: ShareType.Value): Seq[Share] = throw new Exception(errorMessage)

  override def getShare(share: Share): Share = throw new Exception(errorMessage)

  override def getShares(userId: String, shareType: Option[ShareType.Value] = None): Seq[Share] = throw new Exception(errorMessage)

  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))
}

class ShareLogApiServiceSpecShareLogDAO extends MockShareLogDAO {

  override def getShares(userId: String, shareType: Option[ShareType.Value]): Seq[Share] = {
    ElasticSearchShareLogDAOSpecFixtures.fixtureShares
  }
}

class WorkspaceApiServiceSpecShareLogDAO extends MockShareLogDAO {

  override def logShares(userId: String, sharees: Seq[String], shareType: ShareType.Value): Seq[Share] = {
    sharees map{ sharee => Share(userId, sharee, shareType)}
  }
}