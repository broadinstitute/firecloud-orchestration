package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{ImportServiceRequest, AsyncImportRequest, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage

import scala.concurrent.Future

trait ImportServiceDAO {

  def importJob(workspaceNamespace: String,
                workspaceName: String,
                importRequest: AsyncImportRequest,
                isUpsert: Boolean)
               (implicit userInfo: UserInfo): Future[PerRequestMessage]

}
