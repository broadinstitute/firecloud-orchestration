package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.{HttpResponse, StatusCodes}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MockConsentDAO extends ConsentDAO {


  override def getRestriction(orspId: String)(implicit userInfo: WithAccessToken): Future[Option[DuosDataUse]] = {
    orspId match {
      case "MOCK-111" =>
        val ddu = new DuosDataUse(
          commercialUse = Some(true),
          controlSetOption = Some("Yes")
        )
        Future.successful(Some(ddu))
      case "MOCK-NOTFOUND" => Future.failed(new FireCloudExceptionWithErrorReport(FCErrorReport(HttpResponse(StatusCodes.NotFound))))
      case "MOCK-EXCEPTION" => Future.failed(new FireCloudExceptionWithErrorReport(FCErrorReport(HttpResponse(StatusCodes.InternalServerError))))
      case _ => Future.successful(None)
    }


  }

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

}
