package org.broadinstitute.dsde.firecloud.model

import spray.http.OAuth2BearerToken

/**
 * Created by dvoet on 7/21/15.
 *
 * Copied wholesale from rawls on 15-Oct-2015, commit a9664c9f08d0681d6647e6611fd0c785aa8aa24a
 *
 * modified to also include sub, retrieved from the OIDC_CLAIM_sub header.
 * I could have removed the userEmail, accessToken, and accessTokenExpiresIn because we don't
 * use those in orchestration. However, they're quite lightweight, and I've left them in to keep diffs between
 * orchestration and rawls as clean as possible.
 *
 */
case class UserInfo(userEmail: String, accessToken: OAuth2BearerToken, accessTokenExpiresIn: Long, id: String) {
  def getUniqueId = id
}

// response from Google has other fields, but these are the ones we care about
case class OAuthUser(sub: String, email: String)

case class RegistrationInfo(userInfo: RawlsUserInfo, enabled: RawlsEnabled)

case class RawlsUserInfo(userSubjectId: String, userEmail: String)
case class RawlsEnabled(google: Boolean, ldap: Boolean)

