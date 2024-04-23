package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, AsyncImportResponse, ImportServiceListResponse, UserInfo}
import org.databiosphere.workspacedata.client.ApiException
import org.databiosphere.workspacedata.model.GenericJob

trait CwdsDAO {

  def isEnabled: Boolean

  def getSupportedFormats: List[String]

  @throws(classOf[ApiException])
  def listJobsV1(workspaceId: String,
                 runningOnly: Boolean
                )(implicit userInfo: UserInfo): List[ImportServiceListResponse]

  @throws(classOf[ApiException])
  def getJobV1(workspaceId: String,
               jobId: String
              )(implicit userInfo: UserInfo): ImportServiceListResponse

  @throws(classOf[ApiException])
  def importV1(workspaceId: String,
               asyncImportRequest: AsyncImportRequest
              )(implicit userInfo: UserInfo): GenericJob

}
