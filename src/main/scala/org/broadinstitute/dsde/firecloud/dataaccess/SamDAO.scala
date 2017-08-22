package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.Future

/**
  * Created by mbemis on 8/21/17.
  */
object SamDAO {

  lazy val serviceName = "Sam"

}

trait SamDAO extends LazyLogging {

  implicit val errorReportSource = ErrorReportSource(SamDAO.serviceName)

  val samUserRegistrationUrl = FireCloudConfig.Sam.baseUrl + "/register/user"

  def registerUser(userInfo: UserInfo): Future[Unit]

}
