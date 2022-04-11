package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait OauthApiService extends FireCloudDirectives with StandardUserInfoDirectives with SprayJsonSupport {

  implicit val executionContext: ExecutionContext
  val googleOauth = "https://accounts.google.com/o/oauth2/v2/auth"

  val oauthRoutes: Route = {
    path("authorize") {
      get {
        redirect(Uri(googleOauth), StatusCodes.Found)
      }
    } ~
      path("token") {
        post {
          complete(StatusCodes.NoContent)
        }
      }
  }
}
