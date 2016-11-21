package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model._
import org.joda.time.DateTime
import spray.http.OAuth2BearerToken

import scala.concurrent.Future

/**
  * Created by davidan on 9/23/16.
  */

object RawlsDAO {
  lazy val refreshTokenUrl = authedUrl("/user/refreshToken")
  lazy val refreshTokenDateUrl = authedUrl("/user/refreshTokenDate")

  def groupUrl(group: String): String = authedUrl(s"/user/group/$group")
  private def authedUrl(path: String) = pathToUrl(FireCloudConfig.Rawls.authPrefix + path)
  private def pathToUrl(path: String) = FireCloudConfig.Rawls.baseUrl + path
}

trait RawlsDAO extends LazyLogging {

  lazy val rawlsWorkspacesRoot = FireCloudConfig.Rawls.workspacesUrl
  lazy val rawlsAdminUrl = FireCloudConfig.Rawls.authUrl + "/user/role/admin"
  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"
  lazy val rawlsAdminWorkspaces = FireCloudConfig.Rawls.authUrl + "/admin/workspaces?attributeName=library:published&valueBoolean=true"
  lazy val rawlsWorkspaceACLUrl = FireCloudConfig.Rawls.workspacesUrl + "/%s/%s/acl"
  lazy val rawlsBucketUsageUrl = FireCloudConfig.Rawls.workspacesUrl + "/%s/%s/bucketUsage"
  def rawlsEntitiesOfTypeUrl(workspaceNamespace: String, workspaceName: String, entityType: String) = FireCloudConfig.Rawls.workspacesUrl + s"/$workspaceNamespace/$workspaceName/entities/$entityType"

  def isAdmin(userInfo: UserInfo): Future[Boolean]

  def isDbGapAuthorized(userInfo: UserInfo): Future[Boolean]

  def isLibraryCurator(userInfo: UserInfo): Future[Boolean]

  def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[RawlsBucketUsageResponse]

  def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[RawlsWorkspaceResponse]

  def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[RawlsWorkspace]

  def getAllLibraryPublishedWorkspaces: Future[Seq[RawlsWorkspace]]

  def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate])(implicit userToken: WithAccessToken): Future[Seq[WorkspaceACLUpdate]]

  def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[RawlsEntity]]

  def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]]

  def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit]
}
