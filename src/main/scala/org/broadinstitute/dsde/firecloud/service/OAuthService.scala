package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.TokenResponse
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.Uri.{Authority, Host, Path, Query}
import spray.http.{FormData, StatusCodes, Uri}
import spray.httpx.SprayJsonSupport._
import spray.routing._

import scala.concurrent.Future
import scala.util.{Failure, Success}


// see https://developers.google.com/identity/protocols/OAuth2WebServer

trait OAuthService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher

  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    path("login") {
      get {
        headerValueByName("X-Forwarded-Host") { fHost => requestContext =>

          // create the callback url
          // TODO: make scheme dynamic based on request
          val redirectUri = Uri(scheme = "https", Authority(Host(fHost)), Path("/service/oauth2callback"))

          /* configure the login request with:
            - client ID
            - client secret
            - scopes that your app needs to request

            optional:
            - approval_prompt=auto ("force" to get a new refresh token)
            - login_hint=email or sub
            - include_granted_scopes=false ("true" to include previously-granted scopes)
         */
          // TODO: generate and validate unique state param
          // TODO: extract scopes into a constant enum somewhere
          // TODO: login hint, if possible
          // TODO: approval_prompt should not be hardcoded to force
          val authUrl = Uri("https://accounts.google.com/o/oauth2/auth")
            .withQuery(("response_type", "code"),
              ("client_id", FireCloudConfig.Auth.googleClientId),
              ("redirect_uri", redirectUri.toString),
              ("scope", "profile email https://www.googleapis.com/auth/devstorage.full_control https://www.googleapis.com/auth/compute"),
              ("state", "TODO"),
              ("access_type", "offline"))
          requestContext.redirect(authUrl, StatusCodes.TemporaryRedirect)
        }
      }
    } ~
    path("oauth2callback") {
      get {
        /*
      interpret auth server's response
        An error response:
        https://oauth2-login-demo.appspot.com/auth?error=access_denied
      An authorization code response:
        https://oauth2-login-demo.appspot.com/auth?code=4/P7q7W91a-oMsCeLvIaQm6bTrgtp7
      */
        parameters("code", "state") { (code, state) =>
          headerValueByName("X-Forwarded-Host") { fHost => requestContext =>

            // create the callback url
            // TODO: this doesn't seem to be used, but is required?
            val redirectUri = Uri(scheme = "https", Authority(Host(fHost)), Uri.Path("/service/oauth2callback"))

            // create the token exchange url
            val tokenExchangeUrl = Uri("https://www.googleapis.com/oauth2/v3/token")

            // create the post payload for token exchange
            val postData = Map(("code", code),
              ("client_id", FireCloudConfig.Auth.googleClientId),
              ("client_secret", FireCloudConfig.Auth.googleClientSecret),
              ("redirect_uri", redirectUri.toString),
              ("grant_type", "authorization_code"))

            val tokenExchangeRequest = Post(tokenExchangeUrl, FormData(postData))
            val pipeline = sendReceive ~> unmarshal[TokenResponse]
            val futureToken: Future[TokenResponse] = pipeline(tokenExchangeRequest)

            // exchange for a token, then send the access token to the UI
            futureToken onComplete {
              case Success(tr: TokenResponse) => {
                // TODO: make scheme dynamic based on request
                // TODO: what url does the UI want to be redirected to?
                // TODO: drop a cookie instead of sending token in url
                val uiRedirect = Uri(scheme = "https", Authority(Host(fHost)), Path("/login"), Query(("token", tr.access_token)), fragment = None)
                requestContext.redirect(uiRedirect, StatusCodes.TemporaryRedirect)
              }
              // TODO: much, much better error handling
              case Failure(error) => requestContext.complete(InternalServerError, error.toString)
              case _ => requestContext.complete(InternalServerError, "unknown")
            }
          }
        } ~
          parameter("error") { errorMsg =>
            // TODO: much, much better error handling
            complete(StatusCodes.Unauthorized, errorMsg)
          }
      }
    }

}
