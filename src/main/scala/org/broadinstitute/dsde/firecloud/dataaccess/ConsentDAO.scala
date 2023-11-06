package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.DUOS.DuosDataUse
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

object ConsentDAO {
  lazy val serviceName = "Consent"
}

trait ConsentDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(ConsentDAO.serviceName)

  override def serviceName:String = ConsentDAO.serviceName

  def getRestriction(orspId: String)(implicit userInfo: WithAccessToken): Future[Option[DuosDataUse]]

}