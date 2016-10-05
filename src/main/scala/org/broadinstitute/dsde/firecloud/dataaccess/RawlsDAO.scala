package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model.{RawlsWorkspace, RawlsWorkspaceResponse, UserInfo, WorkspaceName}

import scala.concurrent.Future

/**
  * Created by davidan on 9/23/16.
  */
trait RawlsDAO extends LazyLogging {

  lazy final val rawlsWorkspacesRoot = FireCloudConfig.Rawls.workspacesUrl
  lazy final val rawlsAdminUrl = FireCloudConfig.Rawls.authUrl + "/user/role/admin"
  lazy final val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"
  lazy final val rawlsAdminWorkspaces = FireCloudConfig.Rawls.authUrl + "/admin/workspaces"

  def isAdmin(userInfo: UserInfo): Future[Boolean]

  def isLibraryCurator(userInfo: UserInfo): Future[Boolean]

  def getWorkspace(ns: String, name: String)(implicit userInfo: UserInfo): Future[RawlsWorkspaceResponse]

  def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userInfo: UserInfo): Future[RawlsWorkspace]

  def getAllLibraryPublishedWorkspaces: Future[Seq[RawlsWorkspace]]

}
