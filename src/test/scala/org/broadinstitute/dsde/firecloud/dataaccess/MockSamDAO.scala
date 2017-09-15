package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, UserInfo, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}

import scala.concurrent.Future

/**
  * Created by mbemis on 9/7/17.
  */
class MockSamDAO extends SamDAO {

  override def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    Future.successful(RegistrationInfo(WorkbenchUserInfo("foo", "bar"), WorkbenchEnabled(true, true, true)))
  }

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    Future.successful(RegistrationInfo(WorkbenchUserInfo("foo", "bar"), WorkbenchEnabled(true, true, true)))
  }

}
