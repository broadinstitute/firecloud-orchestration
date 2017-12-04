package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, WithAccessToken}
import org.broadinstitute.dsde.rawls.model.{ErrorReportSource, RawlsUserEmail}

import scala.concurrent.Future

/**
  * Created by mbemis on 8/21/17.
  */
object SamDAO {

  lazy val serviceName = "Sam"

}

trait SamDAO extends LazyLogging with ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(SamDAO.serviceName)

  val samUserRegistrationUrl = FireCloudConfig.Sam.baseUrl + "/register/user"
  val samStatusUrl = FireCloudConfig.Sam.baseUrl + "/status"
  val samAdminUserByEmail = FireCloudConfig.Sam.baseUrl + "/api/admin/user/email/%s"

  def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo]
  def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo]

  def adminGetUserByEmail(email: RawlsUserEmail): Future[RegistrationInfo]

  val serviceName = SamDAO.serviceName
}
