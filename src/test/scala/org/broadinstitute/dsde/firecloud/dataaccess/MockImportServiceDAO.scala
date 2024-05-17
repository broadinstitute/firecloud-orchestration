package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.UUID
import akka.http.scaladsl.model.StatusCodes._
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.{FILETYPE_PFB, FILETYPE_RAWLS, FILETYPE_TDR}
import org.broadinstitute.dsde.firecloud.model.{
  AsyncImportRequest, AsyncImportResponse, ImportServiceListResponse,
  UserInfo
}
import org.broadinstitute.dsde.firecloud.service.PerRequest
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.WorkspaceName
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

import scala.concurrent.Future

class MockImportServiceDAO extends ImportServiceDAO {
  override def importJob(workspaceNamespace: String,
                         workspaceName: String,
                         importRequest: AsyncImportRequest,
                         isUpsert: Boolean)
                        (implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = {
    importRequest.filetype match {
      case FILETYPE_PFB | FILETYPE_TDR =>
        if (importRequest.url.contains("forbidden")) Future.successful(RequestComplete(Forbidden, "Missing " +
          "Authorization: Bearer token in header"))
        else if (importRequest.url.contains("bad.request")) Future.successful(RequestComplete(BadRequest, "Bad " +
          "request as reported by import service"))
        else if (importRequest.url.contains("its.lawsuit.time")) Future.successful(RequestComplete
        (UnavailableForLegalReasons, "import service message"))
        else if (importRequest.url.contains("good")) Future.successful(RequestComplete(Accepted,
          AsyncImportResponse(url = importRequest.url,
            jobId = UUID.randomUUID().toString,
            workspace = WorkspaceName(workspaceNamespace, workspaceName))
        ))
        else Future.successful(RequestComplete(EnhanceYourCalm))
      case FILETYPE_RAWLS => ???
      case _ => ???
    }
  }

  override def listJobs(workspaceNamespace: String, workspaceName: String, runningOnly: Boolean)(implicit
                                                                                                 userInfo: UserInfo)
  : Future[List[ImportServiceListResponse]] = {
    Future.successful(List.empty[ImportServiceListResponse])
  }

  override def getJob(workspaceNamespace: String, workspaceName: String, jobId: String)(implicit userInfo: UserInfo)
  : Future[ImportServiceListResponse] = {
    Future.successful(ImportServiceListResponse(jobId, "status", "filetype", None))
  }

  override def isEnabled: Boolean = true
}