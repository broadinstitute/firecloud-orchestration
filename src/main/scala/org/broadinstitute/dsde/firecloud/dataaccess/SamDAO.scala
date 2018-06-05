package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RegistrationInfo, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.rawls.model.{ErrorReportSource, RawlsUserEmail}
import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName

import scala.concurrent.Future

/**
  * Created by mbemis on 8/21/17.
  */
object SamDAO {

  lazy val serviceName = "Sam"

}

trait SamDAO extends LazyLogging with ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(SamDAO.serviceName)

  val managedGroupResourceTypeName = "managed-group"

  val samUserRegistrationUrl = FireCloudConfig.Sam.baseUrl + "/register/user"
  val samStatusUrl = FireCloudConfig.Sam.baseUrl + "/status"
  val samAdminUserByEmail = FireCloudConfig.Sam.baseUrl + "/api/admin/user/email/%s"
  val samArbitraryPetTokenUrl = FireCloudConfig.Sam.baseUrl + "/api/google/v1/user/petServiceAccount/token"

  val samResourceBase: String = FireCloudConfig.Sam.baseUrl + s"/api/resource"
  def samResource(resourceTypeName: String, resourceId: String): String = samResourceBase + s"/$resourceTypeName/$resourceId"
  def samResourceRoles(resourceTypeName: String, resourceId: String): String = samResource(resourceTypeName, resourceId) + "/roles"

  def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo]
  def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo]

  def adminGetUserByEmail(email: RawlsUserEmail): Future[RegistrationInfo]

  def isGroupMember(groupName: WorkbenchGroupName, userInfo: UserInfo): Future[Boolean]

  def getPetServiceAccountTokenForUser(user: WithAccessToken, scopes: Seq[String]): Future[AccessToken]

  val serviceName = SamDAO.serviceName
}
