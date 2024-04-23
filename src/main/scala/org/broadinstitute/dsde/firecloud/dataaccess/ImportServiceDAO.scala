package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, ImportServiceListResponse, ImportServiceRequest, ImportServiceResponse, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage

import scala.concurrent.Future

object ImportServiceFiletypes {
  final val FILETYPE_PFB = "pfb"
  final val FILETYPE_TDR = "tdrexport"
  final val FILETYPE_RAWLS = "rawlsjson"
}

trait ImportServiceDAO {

  def importJob(workspaceNamespace: String,
                workspaceName: String,
                importRequest: AsyncImportRequest,
                isUpsert: Boolean)
               (implicit userInfo: UserInfo): Future[PerRequestMessage]

  def listJobs(workspaceNamespace: String,
               workspaceName: String,
               runningOnly: Boolean
              )(implicit userInfo: UserInfo): Future[List[ImportServiceListResponse]]

  def getJob(workspaceNamespace: String,
             workspaceName: String,
             jobId: String,
            )(implicit userInfo: UserInfo): Future[ImportServiceListResponse]

}
