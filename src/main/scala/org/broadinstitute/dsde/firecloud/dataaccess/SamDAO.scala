package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.broadinstitute.dsde.firecloud.model.{AccessToken, FireCloudManagedGroupMembership, RegistrationInfo, UserIdInfo, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.rawls.model.{ErrorReportSource, RawlsUserEmail}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import spray.json.JsObject

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
  val samGetUserIdsUrl = FireCloudConfig.Sam.baseUrl + "/api/users/v1/%s"
  val samArbitraryPetTokenUrl = FireCloudConfig.Sam.baseUrl + "/api/google/v1/user/petServiceAccount/token"
  val samProjectPetKeyUrl = FireCloudConfig.Sam.baseUrl + "/api/google/v1/user/petServiceAccount/%s/key"

  val samManagedGroupsBase: String = FireCloudConfig.Sam.baseUrl + "/api/groups"
  val samManagedGroupBase: String = FireCloudConfig.Sam.baseUrl + "/api/group"
  def samManagedGroup(groupName: WorkbenchGroupName): String = samManagedGroupBase + s"/$groupName"
  def samManagedGroupRequestAccess(groupName: WorkbenchGroupName): String = samManagedGroup(groupName) + "/requestAccess"
  def samManagedGroupPolicy(groupName: WorkbenchGroupName, policyName: ManagedGroupRole): String = samManagedGroup(groupName) + s"/$policyName"
  def samManagedGroupAlterMember(groupName: WorkbenchGroupName, policyName: ManagedGroupRole, email: WorkbenchEmail): String = samManagedGroupPolicy(groupName, policyName) + s"/$email"

  val samResourceBase: String = FireCloudConfig.Sam.baseUrl + s"/api/resource"
  def samResource(resourceTypeName: String, resourceId: String): String = samResourceBase + s"/$resourceTypeName/$resourceId"
  def samResourceRoles(resourceTypeName: String, resourceId: String): String = samResource(resourceTypeName, resourceId) + "/roles"
  def samResourcePolicies(resourceTypeName: String, resourceId: String): String = samResource(resourceTypeName, resourceId) + "/policies"
  def samResourcePolicy(resourceTypeName: String, resourceId: String, policyName: String): String = samResourcePolicies(resourceTypeName, resourceId) + s"/$policyName"
  def samResourcePolicyAlterMember(resourceTypeName: String, resourceId: String, policyName: String, email: WorkbenchEmail): String = samResourcePolicy(resourceTypeName, resourceId, policyName) + s"/$email"


  val samResourcesBase: String = FireCloudConfig.Sam.baseUrl + s"/api/resources/v1"
  def samListResources(resourceTypeName: String): String = samResourcesBase + s"/$resourceTypeName"

  def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo]
  def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo]

  def getUserIds(email: RawlsUserEmail)(implicit userInfo: WithAccessToken): Future[UserIdInfo]

  def listWorkspaceResources(implicit userInfo: WithAccessToken): Future[Seq[UserPolicy]]

  def createGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit]
  def deleteGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit]
  def listGroups(implicit userInfo: WithAccessToken): Future[List[FireCloudManagedGroupMembership]]
  def getGroupEmail(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[WorkbenchEmail]
  def isGroupMember(groupName: WorkbenchGroupName, userInfo: UserInfo): Future[Boolean]
  def listGroupPolicyEmails(groupName: WorkbenchGroupName, policyName: ManagedGroupRole)(implicit userInfo: WithAccessToken): Future[List[WorkbenchEmail]]
  def addGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit]
  def removeGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit]
  def overwriteGroupMembers(groupName: WorkbenchGroupName, role: ManagedGroupRole, memberList: List[WorkbenchEmail])(implicit userInfo: WithAccessToken): Future[Unit]
  def requestGroupAccess(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit]

  def addPolicyMember(resourceTypeName: String, resourceId: String, policyName: String, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit]
  def setPolicyPublic(resourceTypeName: String, resourceId: String, policyName: String, public: Boolean)(implicit userInfo: WithAccessToken): Future[Unit]

  def getPetServiceAccountTokenForUser(user: WithAccessToken, scopes: Seq[String]): Future[AccessToken]
  def getPetServiceAccountKey(googleProject: String)(implicit userInfo: WithAccessToken): Future[JsObject]

  val serviceName = SamDAO.serviceName
}
