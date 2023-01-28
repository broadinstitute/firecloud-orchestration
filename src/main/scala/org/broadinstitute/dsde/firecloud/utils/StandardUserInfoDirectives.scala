package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{headerValueByName, optionalHeaderValueByName}
import org.broadinstitute.dsde.firecloud.model.UserInfo

trait StandardUserInfoDirectives extends UserInfoDirectives {

  // The OAUTH2_CLAIM_google_id header is populated when a user signs in to Google via B2C.
  // If present, use that value instead of the B2C id for backwards compatibility.
  def requireUserInfo(): Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
      headerValueByName("OIDC_CLAIM_user_id") &
      headerValueByName("OIDC_CLAIM_expires_in") &
      headerValueByName("OIDC_CLAIM_email") &
      optionalHeaderValueByName("OAUTH2_CLAIM_google_id") &
      optionalHeaderValueByName("OAUTH2_CLAIM_idp_access_token")
    ) tmap {
      case (token, userId, expiresIn, email, googleIdOpt, googleTokenOpt) => {
        UserInfo(email, OAuth2BearerToken(token), expiresIn.toLong, googleIdOpt.getOrElse(userId), googleTokenOpt.map(OAuth2BearerToken))
      }
    }
}
