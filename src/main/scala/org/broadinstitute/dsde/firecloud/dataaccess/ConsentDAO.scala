package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.rawls.model.ErrorReportSource

object ConsentDAO {
  lazy val serviceName = "Consent"
}

trait ConsentDAO extends ReportsSubsystemStatus {
  implicit val errorReportSource = ErrorReportSource(ConsentDAO.serviceName)

  override def serviceName:String = ConsentDAO.serviceName

}