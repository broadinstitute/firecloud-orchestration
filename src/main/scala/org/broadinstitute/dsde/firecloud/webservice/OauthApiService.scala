package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

/**
  * These two routes are no-ops. The backend behavior they previously supported is no longer necessary:
  * - reporting on how old the user's refresh token is;
  * - retrieving a new oauth token from Google and sending it to Rawls to store
  *
  * In the spirit of backwards compatibility, we are not (yet) removing these APIs. Instead, we hardcode
  * them to respond as if they were successful, without performing any backend work.
  */
trait OauthApiService extends FireCloudDirectives with StandardUserInfoDirectives with SprayJsonSupport {

  implicit val executionContext: ExecutionContext

  val oauthRoutes: Route =
    path("handle-oauth-code") {
      post {
        complete(StatusCodes.NoContent)
      }
    } ~
      path("api" / "refresh-token-status") {
        get {
          requireUserInfo() { _ =>
            complete { RequestComplete(StatusCodes.OK, Map("requiresRefresh" -> false)) }
          }
        }
      }
}
