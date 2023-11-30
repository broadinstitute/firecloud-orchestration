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

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

}
