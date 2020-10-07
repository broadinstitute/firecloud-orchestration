package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{ImportServiceRequest, PfbImportRequest, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage

import scala.concurrent.Future

trait ImportServiceDAO {

  def importPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequestMessage]

}
