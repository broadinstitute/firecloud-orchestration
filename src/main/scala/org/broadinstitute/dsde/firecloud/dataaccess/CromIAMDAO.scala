package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

object CromIAMDAO {
  lazy val serviceName = "CromIAM"
}

trait CromIAMDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(CromIAMDAO.serviceName)

  def submit(wdlUrl: String, inputs: String, options: Option[String])(implicit userToken: WithAccessToken): Future[String]

  override def serviceName: String = CromIAMDAO.serviceName
}
