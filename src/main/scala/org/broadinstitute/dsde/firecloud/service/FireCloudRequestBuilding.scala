package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import spray.http.HttpHeaders.{Authorization, RawHeader}
import spray.http._
import spray.routing.RequestContext


trait FireCloudRequestBuilding extends spray.httpx.RequestBuilding {

  val fireCloudHeader = HttpHeaders.RawHeader("X-FireCloud-Id", FireCloudConfig.FireCloud.fireCloudId)

  // TODO: would be much better to make requestContext implicit, so callers don't have to always pass it in
  // TODO: this could probably be rewritten more tersely in idiomatic scala - for instance, don't create
    // the OAuth2BearerToken if we're not going to use it. I'm leaving all this longhand for better comprehension.
  def authHeaders(requestContext: RequestContext) = {

    // inspect headers for a pre-existing Authorization: header
    val authorizationHeader: Option[HttpCredentials] = (requestContext.request.headers collect {
        case Authorization(h) => h
    }).headOption

    authorizationHeader match {
      // if we have authorization credentials, apply them to the outgoing request
      case Some(c) => addCredentials(c) ~> addFireCloudCredentials
      // else, noop. But the noop needs to return an identity function in order to compile.
      // alternately, we could throw an error here, since we assume some authorization should exist.
      case None => (r: HttpRequest) => r ~> addFireCloudCredentials
    }

  }

  def dummyAuthHeaders = {
    addCredentials(OAuth2BearerToken("mF_9.B5f-4.1JqM"))
  }

  def dummyUserIdHeaders(userId: String, token: String = "access_token") = {
    addCredentials(OAuth2BearerToken("mF_9.B5f-4.1JqM")) ~>
      addHeader(RawHeader("OIDC_CLAIM_user_id", userId)) ~>
      addHeader(RawHeader("OIDC_access_token", token)) ~>
      addHeader(RawHeader("OIDC_CLAIM_email", "random@site.com")) ~>
      addHeader(RawHeader("OIDC_CLAIM_expires_in", "100000"))
  }

  // with great power comes great responsibility!
  def addAdminCredentials = addCredentials(OAuth2BearerToken(HttpGoogleServicesDAO.getAdminUserAccessToken))

  def addFireCloudCredentials = addHeader(fireCloudHeader)

}
