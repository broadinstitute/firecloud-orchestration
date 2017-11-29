package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, SubsystemStatus, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail

import scala.concurrent.Future

/**
  * Created by mbemis on 9/7/17.
  */
class MockSamDAO extends SamDAO {

  override def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = enabledUserInfo

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = enabledUserInfo

  override def adminGetUserByEmail(email: RawlsUserEmail): Future[RegistrationInfo] = enabledUserInfo

  override def status: Future[SubsystemStatus] = Future.successful(SubsystemStatus(ok = true, messages = None))

  private val enabledUserInfo = Future.successful {
      RegistrationInfo(
        WorkbenchUserInfo(userSubjectId = "foo", userEmail = "bar"),
        WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true))
  }
}
