package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, AsyncImportResponse, ImportServiceListResponse, UserInfo}
import org.databiosphere.workspacedata.model.GenericJob

trait CwdsDAO {

  def isEnabled: Boolean

  def listJobsV1(workspaceId: String,
                 runningOnly: Boolean
                )(implicit userInfo: UserInfo): List[ImportServiceListResponse]

  def importV1(workspaceId: String,
               importRequest: AsyncImportRequest
              )(implicit userInfo: UserInfo): GenericJob

}
