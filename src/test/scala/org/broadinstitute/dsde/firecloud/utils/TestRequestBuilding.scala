package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import spray.http.HttpHeaders.{Cookie, RawHeader}
import spray.http._

trait TestRequestBuilding extends FireCloudRequestBuilding {

  val dummyToken: String = "mF_9.B5f-4.1JqM"

  def dummyAuthHeaders: RequestTransformer = {
    addCredentials(OAuth2BearerToken(dummyToken))
  }

  def dummyUserIdHeaders(userId: String, token: String = "access_token", email: String = "random@site.com"): WithTransformerConcatenation[HttpRequest, HttpRequest] = {
    addCredentials(OAuth2BearerToken(dummyToken)) ~>
      addHeader(RawHeader("OIDC_CLAIM_user_id", userId)) ~>
      addHeader(RawHeader("OIDC_access_token", token)) ~>
      addHeader(RawHeader("OIDC_CLAIM_email", email)) ~>
      addHeader(RawHeader("OIDC_CLAIM_expires_in", "100000"))
  }

  def dummyCookieAuthHeaders: RequestTransformer = {
    addHeader(Cookie(HttpCookie("FCtoken", dummyToken)))
  }
}
