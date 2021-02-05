package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

trait ShibbolethDAO {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource("shibboleth")

  def getPublicKey(): Future[String]

}
