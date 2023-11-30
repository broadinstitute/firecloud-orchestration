package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

object ConsentDAO {
  lazy val serviceName = "Consent"
}

// TODO: AJ-1488: if we don't use ConsentDAO at all, can we remove it entirely? Can we remove it from Orch's status?
// TODO: AJ-1488: are there config values we can remove too?
trait ConsentDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(ConsentDAO.serviceName)

  override def serviceName:String = ConsentDAO.serviceName

}