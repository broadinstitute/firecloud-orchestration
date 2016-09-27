package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{RawlsWorkspace, RawlsWorkspaceResponse, UserInfo, WorkspaceName}

import scala.concurrent.Future

/**
  * Created by davidan on 9/23/16.
  */
abstract class RawlsDAO {

  lazy val rawlsWorkspacesRoot = FireCloudConfig.Rawls.workspacesUrl
  lazy val rawlsCuratorUrl = FireCloudConfig.Rawls.authUrl + "/user/role/curator"

  def isLibraryCurator(userInfo: UserInfo): Future[Boolean]

  def withWorkspaceResponse(wsid: WorkspaceName)(implicit userInfo: UserInfo): Future[RawlsWorkspaceResponse]

}
