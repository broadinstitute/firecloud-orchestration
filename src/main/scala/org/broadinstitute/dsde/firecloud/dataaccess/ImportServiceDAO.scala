package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{ImportServiceRequest, AsyncImportRequest, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage

import scala.concurrent.Future

trait ImportServiceDAO {

  def importPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: AsyncImportRequest)(implicit userInfo: UserInfo): Future[PerRequestMessage]

  def importRawlsJson(workspaceNamespace: String, workspaceName: String, rawlsJsonRequest: AsyncImportRequest)(implicit userInfo: UserInfo): Future[PerRequestMessage]

}
