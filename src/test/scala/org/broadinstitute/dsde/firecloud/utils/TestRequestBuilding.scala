package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.{Cookie, OAuth2BearerToken, RawHeader}
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding

trait TestRequestBuilding extends FireCloudRequestBuilding {

  val dummyToken: String = "mF_9.B5f-4.1JqM"

  def dummyAuthHeaders: RequestTransformer = {
    addCredentials(OAuth2BearerToken(dummyToken))
  }

  def dummyUserIdHeaders(userId: String, token: String = "access_token", email: String = "random@site.com"): WithTransformerConcatenation[HttpRequest, HttpRequest] = {
    addCredentials(OAuth2BearerToken(token)) ~>
      addHeader(RawHeader("OIDC_CLAIM_user_id", userId)) ~>
      addHeader(RawHeader("OIDC_access_token", token)) ~>
      addHeader(RawHeader("OIDC_CLAIM_email", email)) ~>
      addHeader(RawHeader("OIDC_CLAIM_expires_in", "100000"))
  }

  def dummyCookieAuthHeaders: RequestTransformer = {
    addHeader(Cookie("FCtoken", dummyToken))
  }
}
