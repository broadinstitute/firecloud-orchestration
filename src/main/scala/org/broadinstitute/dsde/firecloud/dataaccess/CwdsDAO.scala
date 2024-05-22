package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, CwdsListResponse, UserInfo}
import org.databiosphere.workspacedata.client.ApiException
import org.databiosphere.workspacedata.model.GenericJob
object LegacyFileTypes {
  final val FILETYPE_PFB = "pfb"
  final val FILETYPE_TDR = "tdrexport"
  final val FILETYPE_RAWLS = "rawlsjson"
}

trait CwdsDAO {

  def isEnabled: Boolean

  def getSupportedFormats: List[String]

  @throws(classOf[ApiException])
  def listJobsV1(workspaceId: String,
                 runningOnly: Boolean
                )(implicit userInfo: UserInfo): List[CwdsListResponse]

  @throws(classOf[ApiException])
  def getJobV1(workspaceId: String,
               jobId: String
              )(implicit userInfo: UserInfo): CwdsListResponse

  @throws(classOf[ApiException])
  def importV1(workspaceId: String,
               asyncImportRequest: AsyncImportRequest
              )(implicit userInfo: UserInfo): GenericJob

}
