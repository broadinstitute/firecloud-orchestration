package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess, optionalHeaderValueByName}
import org.broadinstitute.dsde.firecloud.model.UserInfo

import scala.concurrent.Future

trait StandardUserInfoDirectives extends UserInfoDirectives {

  // Note the OAUTH2_CLAIM_google_id header is populated when a user logs in to Google
  // via B2C. We use that for the user id if present to map B2C logins to existing
  // Google users in the system.
  def requireUserInfo(): Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
      headerValueByName("OIDC_CLAIM_user_id") &
      headerValueByName("OIDC_CLAIM_expires_in") &
      headerValueByName("OIDC_CLAIM_email") &
      optionalHeaderValueByName("OAUTH2_CLAIM_google_id")
    ) tmap {
      case (token, userId, expiresIn, email, googleIdOpt) => {
        UserInfo(email, OAuth2BearerToken(token), expiresIn.toLong, googleIdOpt.getOrElse(userId))
      }
    }
}
