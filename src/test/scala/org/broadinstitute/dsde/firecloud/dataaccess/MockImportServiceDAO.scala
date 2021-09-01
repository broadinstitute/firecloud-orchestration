package org.broadinstitute.dsde.firecloud.dataaccess
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes._
import org.broadinstitute.dsde.firecloud.model.{PfbImportRequest, PfbImportResponse, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.WorkspaceName
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

import scala.concurrent.Future

class MockImportServiceDAO extends ImportServiceDAO {
  override def importPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = {
    pfbRequest.url match {
      case Some(url) => {
        if(url.contains("forbidden")) Future.successful(RequestComplete(Forbidden, "Missing Authorization: Bearer token in header"))
        else if(url.contains("bad.request")) Future.successful(RequestComplete(BadRequest, "Bad request as reported by import service"))
        else if(url.contains("its.lawsuit.time")) Future.successful(RequestComplete(UnavailableForLegalReasons, "import service message"))
        else if(url.contains("good")) Future.successful(RequestComplete(Accepted,
          PfbImportResponse(url = pfbRequest.url.getOrElse(""),
            jobId = UUID.randomUUID().toString,
            workspace = WorkspaceName(workspaceNamespace, workspaceName))
          )

        )
        else Future.successful(RequestComplete(EnhanceYourCalm))
      }
      case None => Future.successful(RequestComplete(EnhanceYourCalm))
    }
  }

  override def importBatchUpsertJson(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = ???
}
