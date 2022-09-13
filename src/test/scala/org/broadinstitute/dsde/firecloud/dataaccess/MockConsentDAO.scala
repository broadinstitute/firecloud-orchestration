package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.broadinstitute.dsde.rawls.model.ErrorReport

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
      case "MOCK-NOTFOUND" => Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Not Found from Mock")))
      case "MOCK-EXCEPTION" => Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Internal Server Error from Mock")))
      case _ => Future.successful(None)
    }


  }

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

}
