package org.broadinstitute.dsde.firecloud.dataaccess
import org.broadinstitute.dsde.firecloud.model.{PfbImportRequest, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest

import scala.concurrent.Future

class MockImportServiceDAO extends ImportServiceDAO {
  override def importPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = ???
}
