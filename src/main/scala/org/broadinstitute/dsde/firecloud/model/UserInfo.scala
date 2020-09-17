package org.broadinstitute.dsde.firecloud.model

import akka.http.scaladsl.model.headers.OAuth2BearerToken
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
  * Amended 11/16/2016:
  *  Added trait WithAccessToken, which is extended by the existing UserInfo class as well as a new AccessToken class
  *  This is so that we can use AccessToken for cookies we get from the browser, which do not come with the fields in
  *  UserInfo.
 */
trait WithAccessToken { val accessToken : OAuth2BearerToken }

case class UserInfo(userEmail: String, accessToken: OAuth2BearerToken, accessTokenExpiresIn: Long, id: String) extends WithAccessToken {
  def getUniqueId = id
}

object UserInfo {
  def apply(accessToken: String, subjectId: String): UserInfo =
    UserInfo("", OAuth2BearerToken(accessToken), -1, subjectId)
}

case class AccessToken(accessToken: OAuth2BearerToken) extends WithAccessToken
object AccessToken{
  def apply(tokenStr: String) = new AccessToken(OAuth2BearerToken(tokenStr))
}

// response from Google has other fields, but these are the ones we care about
case class OAuthUser(sub: String, email: String)

case class RegistrationInfo(userInfo: WorkbenchUserInfo, enabled: WorkbenchEnabled, messages:Option[List[String]] = None)
case class RegistrationInfoV2(userSubjectId: String, userEmail: String, enabled: Boolean)

case class UserIdInfo(userSubjectId: String, userEmail: String, googleSubjectId: String)

case class WorkbenchUserInfo(userSubjectId: String, userEmail: String)
case class WorkbenchEnabled(google: Boolean, ldap: Boolean, allUsersGroup: Boolean)
case class WorkbenchEnabledV2(enabled: Boolean, inAllUsersGroup: Boolean, inGoogleProxyGroup: Boolean)

// TODO: roll into RawlsEnabled? combine with an isAdmin role?
case class Curator(curator: Boolean)

// indicates whether or not the user can import (workflow|data|etc) into a workspace - the user
// must have either a writable workspace or the ability to create a workspace (ready billing project)
case class UserImportPermission(billingProject: Boolean, writableWorkspace: Boolean)

