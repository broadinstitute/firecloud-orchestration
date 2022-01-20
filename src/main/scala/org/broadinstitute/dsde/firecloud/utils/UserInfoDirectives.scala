package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.server.Directive1
import org.broadinstitute.dsde.firecloud.model.UserInfo

import scala.concurrent.ExecutionContext

/**
  * Directives to get user information.
  *
  * Copied wholesale from rawls on 15-Oct-2015, commit a9664c9f08d0681d6647e6611fd0c785aa8aa24a
  */
trait UserInfoDirectives {
  def requireUserInfo(): Directive1[UserInfo]
}
