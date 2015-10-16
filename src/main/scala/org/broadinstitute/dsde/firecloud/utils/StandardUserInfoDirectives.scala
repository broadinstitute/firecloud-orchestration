package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.vault.common.util.ImplicitMagnet
import spray.http.OAuth2BearerToken
import spray.routing.Directive1
import spray.routing.Directives._

import scala.concurrent.ExecutionContext

/**
 *
 * Copied wholesale from rawls on 15-Oct-2015, commit a9664c9f08d0681d6647e6611fd0c785aa8aa24a
 *
 * modified to also retrieve OIDC_CLAIM_sub.
 * I could have removed the OIDC_access_token, OIDC_CLAIM_expires_in, and OIDC_CLAIM_email because we don't
 * use those in orchestration. However, they're quite lightweight, and I've left them in to keep diffs between
 * orchestration and rawls as clean as possible.
 *
 */
trait StandardUserInfoDirectives extends UserInfoDirectives {

  def requireUserInfo(magnet: ImplicitMagnet[ExecutionContext]): Directive1[UserInfo] = {
    implicit val ec = magnet.value
    for(accessToken <- accessTokenHeaderDirective;
        userEmail <- emailHeaderDirective;
        accessTokenExpiresIn <- accessTokenExpiresInHeaderDirective;
        sub <- subHeaderDirective
    ) yield UserInfo(userEmail, OAuth2BearerToken(accessToken), accessTokenExpiresIn.toLong, sub)
  }

  private def accessTokenHeaderDirective: Directive1[String] = headerValueByName("OIDC_access_token")
  private def accessTokenExpiresInHeaderDirective: Directive1[String] = headerValueByName("OIDC_CLAIM_expires_in")
  private def emailHeaderDirective: Directive1[String] = headerValueByName("OIDC_CLAIM_email")
  private def subHeaderDirective: Directive1[String] = headerValueByName("OIDC_CLAIM_sub")
}
