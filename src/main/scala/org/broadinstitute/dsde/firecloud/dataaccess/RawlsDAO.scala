package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
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
  lazy val rawlsWorkspaceACLUrl = FireCloudConfig.Rawls.workspacesUrl + "/%s/%s/acl?inviteUsersNotFound=%s"
  def rawlsBucketUsageUrl(workspaceNamespace: String, workspaceName: String) = FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/bucketUsage"

  def rawlsEntitiesOfTypeUrl(workspaceNamespace: String, workspaceName: String, entityType: String) = FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/entities/$entityType"

  def isRegistered(userInfo: UserInfo): Future[Boolean]

  def isAdmin(userInfo: UserInfo): Future[Boolean]

  def isGroupMember(userInfo: UserInfo, groupName: String): Future[Boolean]

  def isLibraryCurator(userInfo: UserInfo): Future[Boolean]

  def registerUser(userInfo: UserInfo): Future[Unit]

  def getGroupsForUser(implicit userToken: WithAccessToken): Future[Seq[String]]

  def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse]

  def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]]

  def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse]

  def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace]

  def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace]

  def getAllLibraryPublishedWorkspaces: Future[Seq[Workspace]]

  def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList]

  def adminAddMemberToGroup(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean]

  def adminOverwriteGroupMembership(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean]

  def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userToken: UserInfo): Future[Seq[Entity]]

  def queryEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery)(implicit userToken: UserInfo): Future[EntityQueryResponse]

  def getEntityTypes(workspaceNamespace: String, workspaceName: String)(implicit userToken: UserInfo): Future[Map[String, EntityTypeMetadata]]

  def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]]

  def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit]

  def getCatalog(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Seq[WorkspaceCatalog]]

  def patchCatalog(workspaceNamespace: String, workspaceName: String, updates: Seq[WorkspaceCatalog])(implicit userToken: WithAccessToken): Future[WorkspaceCatalogUpdateResponseList]
}
