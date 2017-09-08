package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, UserInfo, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}

import scala.concurrent.Future

/**
  * Created by mbemis on 9/7/17.
  */
class MockSamDAO extends SamDAO {

  override def registerUser(userInfo: UserInfo): Future[Unit] = {
    Future.successful()
  }

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    val x = RegistrationInfo(WorkbenchUserInfo("foo", "bar"), WorkbenchEnabled(true, true, true))
    Future.successful(x)
  }

}
