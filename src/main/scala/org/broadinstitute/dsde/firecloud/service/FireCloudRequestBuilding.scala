package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO._
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.{Authorization, HttpCredentials, OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.server.RequestContext


trait FireCloudRequestBuilding extends RequestBuilding {

  val fireCloudHeader = RawHeader("X-FireCloud-Id", FireCloudConfig.FireCloud.fireCloudId)

  def authHeaders(credentials:Option[HttpCredentials]): HttpRequest => HttpRequest = {
    credentials match {
      // if we have authorization credentials, apply them to the outgoing request
      case Some(c) => addCredentials(c) ~> addFireCloudCredentials
      // else, noop. But the noop needs to return an identity function in order to compile.
      // alternately, we could throw an error here, since we assume some authorization should exist.
      case None => (r: HttpRequest) => r ~> addFireCloudCredentials
    }
  }

  def authHeaders(requestContext: RequestContext): HttpRequest => HttpRequest = {
    // inspect headers for a pre-existing Authorization: header
    val authorizationHeader: Option[HttpCredentials] = requestContext.request.headers collectFirst {
      case Authorization(h) => h
    }
    authHeaders(authorizationHeader)
  }

  def authHeaders(accessToken: WithAccessToken): HttpRequest => HttpRequest = {
    authHeaders(Some(accessToken.accessToken))
  }

  // with great power comes great responsibility!
  def addAdminCredentials = addCredentials(OAuth2BearerToken(HttpGoogleServicesDAO.getAdminUserAccessToken))

  def addFireCloudCredentials = addHeader(fireCloudHeader)

}
