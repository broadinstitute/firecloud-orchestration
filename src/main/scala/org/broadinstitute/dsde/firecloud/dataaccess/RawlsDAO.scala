package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraConfigurationShort
import org.broadinstitute.dsde.firecloud.model.Metrics.AdminStats
import org.broadinstitute.dsde.firecloud.model.Trial.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Trial.{RawlsBillingProjectMember, RawlsBillingProjectMembership}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.joda.time.DateTime

import scala.concurrent.Future

/**
  * Created by davidan on 9/23/16.
  */

object RawlsDAO {
  lazy val refreshTokenUrl = authedUrl("/user/refreshToken")
  lazy val refreshTokenDateUrl = authedUrl("/user/refreshTokenDate")

  lazy val serviceName = "Rawls"

  def groupUrl(group: String): String = authedUrl(s"/user/group/$group")
  private def authedUrl(path: String) = pathToUrl(FireCloudConfig.Rawls.authPrefix + path)
  private def pathToUrl(path: String) = FireCloudConfig.Rawls.baseUrl + path
}

trait RawlsDAO extends LazyLogging with ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(RawlsDAO.serviceName)

  lazy val rawlsUserRegistrationUrl = FireCloudConfig.Rawls.baseUrl + "/register/user"
  lazy val rawlsWorkspacesRoot = FireCloudConfig.Rawls.workspacesUrl
  lazy val rawlsAdminUrl = FireCloudConfig.Rawls.authUrl + "/user/role/admin"
  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"
  lazy val rawlsGroupsForUserUrl = FireCloudConfig.Rawls.authUrl + "/user/groups"
  lazy val rawlsWorkpacesUrl = FireCloudConfig.Rawls.workspacesUrl
  lazy val rawlsAdminWorkspaces = FireCloudConfig.Rawls.authUrl + "/admin/workspaces?attributeName=library:published&valueBoolean=true"
  lazy val rawlsWorkspaceACLUrl = FireCloudConfig.Rawls.workspacesUrl + "/%s/%s/acl"
  lazy val rawlsWorkspaceACLQuerystring = "?inviteUsersNotFound=%s"
  lazy val rawlsWorkspaceMethodConfigsUrl = FireCloudConfig.Rawls.workspacesUrl + "/%s/%s/methodconfigs"
  def rawlsBucketUsageUrl(workspaceNamespace: String, workspaceName: String) = FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/bucketUsage"

  def rawlsEntitiesOfTypeUrl(workspaceNamespace: String, workspaceName: String, entityType: String) = FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/entities/$entityType"

  def isAdmin(userInfo: UserInfo): Future[Boolean]

  def isLibraryCurator(userInfo: UserInfo): Future[Boolean]

  def registerUser(userInfo: UserInfo): Future[Unit]

  def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse]

  def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]]

  def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse]

  def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace]

  def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace]

  // you must be an admin to execute this method
  def getAllLibraryPublishedWorkspaces(implicit userToken: WithAccessToken): Future[Seq[Workspace]]

  def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceACL]

  def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList]

  def adminStats(startDate: DateTime, endDate: DateTime, workspaceNamespace: Option[String], workspaceName: Option[String]): Future[AdminStats]

  def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userToken: UserInfo): Future[Seq[Entity]]

  def queryEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery)(implicit userToken: UserInfo): Future[EntityQueryResponse]

  def getEntityTypes(workspaceNamespace: String, workspaceName: String)(implicit userToken: UserInfo): Future[Map[String, EntityTypeMetadata]]

  def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]]

  def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit]

  def getCatalog(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Seq[WorkspaceCatalog]]

  def patchCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog])(implicit userToken: WithAccessToken): Future[WorkspaceCatalogUpdateResponseList]

  def getAgoraMethodConfigs(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Seq[AgoraConfigurationShort]]

  def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[WorkspaceDeleteResponse]

  def createProject(projectName: String, billingAccount: String)(implicit userToken: WithAccessToken): Future[Boolean]
  def getProjects(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMembership]]

  def getProjectMembers(projectId: String)(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMember]]

  def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean]

  def removeUserFromBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean]

  override def serviceName:String = RawlsDAO.serviceName

}
