package org.broadinstitute.dsde.firecloud.service

import spray.http.HttpHeaders.Authorization
import spray.http.{HttpCredentials, HttpRequest, OAuth2BearerToken}
import spray.routing.RequestContext


trait FireCloudRequestBuilding extends spray.httpx.RequestBuilding {

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
      case Some(c) => addCredentials(c)
      // else, noop. But the noop needs to return an identity function in order to compile.
      // alternately, we could throw an error here, since we assume some authorization should exist.
      case None => (r: HttpRequest) => r
    }

  }

  def dummyAuthHeaders = {
    addCredentials(OAuth2BearerToken("mF_9.B5f-4.1JqM"))
  }

}
