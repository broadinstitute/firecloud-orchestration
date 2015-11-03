package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.OAuthException
import org.slf4j.LoggerFactory
import spray.http.Uri._
import spray.http.{StatusCodes, Uri}
import spray.routing._

// see https://developers.google.com/identity/protocols/OAuth2WebServer

trait OAuthService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    path("login") {
      get {
        parameter("path") { userpath =>
          // TODO: future story: generate and persist unique token along with the user path
          val state = userpath

          // approval prompt - "force" to force a refresh token
          // TODO: future story: make dynamic based on whether or not the user has a refresh token stored already
          val approvalPrompt = "auto"

          try {
            // create the authentication url and redirect the browser
            val gcsAuthUrl = HttpGoogleServicesDAO.getGoogleRedirectURI(state, approvalPrompt)
            redirect(gcsAuthUrl, StatusCodes.TemporaryRedirect)
          } catch {
            /* we don't expect any exceptions here; if we get any, it's likely
                a misconfiguration of our client id/secrets/callback. But since this is about
                authentication, we are extra careful.
             */
            case e: Exception => {
              log.error("problem during OAuth redirect", e)
              // TODO: here and elsewhere, redirect to a UI error page instead of issuing a 401?
              complete(StatusCodes.Unauthorized, e.getMessage)
            }
          }
        }
      }
    } ~
    path("oauth2callback") {
      get {
        /*
          interpret auth server's response
            An error response has "?error=access_denied"
            An authorization code response has "?code=4/P7q7W91a-oMsCeLvIaQm6bTrgtp7"
        */
        parameters("code", "state") { (code, actualState) =>
          // TODO: future story: use expected-state we previously persisted
          val expectedState = actualState

          try {
            // is it worth breaking this out into an async/perrequest actor, so we don't block a thread?
            // not sure what the google library does under the covers - is it blocking?
            val gcsTokenResponse = HttpGoogleServicesDAO.getTokens(actualState, expectedState, code)
            val accessToken = gcsTokenResponse.access_token

            val uiRedirect = Uri(FireCloudConfig.FireCloud.baseUrl)
              .withFragment(actualState)
              .withQuery(("token", accessToken))
            redirect(uiRedirect, StatusCodes.TemporaryRedirect)
          } catch {
            case e:OAuthException => complete(StatusCodes.Unauthorized, e.getMessage) // these can be shown to the user
            case e: Exception => {
              log.error("problem during OAuth code exchange", e)
              complete(StatusCodes.Unauthorized, e.getMessage)
            }
          }
        } ~
          parameter("error") { errorMsg =>
            // echo the oauth error back to the user. Is that safe to do?
            complete(StatusCodes.Unauthorized, errorMsg)
          }
      }
    }

}
