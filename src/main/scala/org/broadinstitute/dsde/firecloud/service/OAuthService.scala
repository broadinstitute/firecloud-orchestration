package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RawlsToken, OAuthException}
import org.slf4j.LoggerFactory
import spray.http.Uri._
import spray.http.{HttpResponse, OAuth2BearerToken, StatusCodes, Uri}
import spray.routing._
import spray.client.pipelining._
import spray.httpx.SprayJsonSupport._

import scala.concurrent.Future
import scala.util.{Failure, Success}

// see https://developers.google.com/identity/protocols/OAuth2WebServer

object OAuthService {
  val remoteTokenPutPath = FireCloudConfig.Rawls.authPrefix + "/user/refreshToken"
  val remoteTokenPutUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenPutPath

  val remoteTokenDatePath = FireCloudConfig.Rawls.authPrefix + "/user/rereshTokenDate"
  val remoteTokenDateUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenDatePath

}

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
          val approvalPrompt = "force"

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
              complete(StatusCodes.Unauthorized, e.getMessage)
            }
          }
        }
      }
    } ~
    path("callback") {
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
            val refreshToken = gcsTokenResponse.refresh_token

            // if we have a refresh token, store it in rawls
            refreshToken map {rt =>
              val tokenReq = Put(OAuthService.remoteTokenPutUrl, RawlsToken(accessToken))
              // we can't use the standard externalHttpPerRequest here, because the requestContext doesn't have the
              // access token yet; we have to add the token manually
              val pipeline = addCredentials(OAuth2BearerToken(accessToken)) ~> sendReceive
              val tokenStoreFuture: Future[HttpResponse] = pipeline { tokenReq }

              // we intentionally don't gate the login process on storage of the refresh token. Token storage
              // will happen async. If token storage fails, we rely on underlying services to notice the
              // missing token and re-initiate the oauth grants.
              tokenStoreFuture onComplete {
                case Success(response) => {
                  response.status match {
                    case StatusCodes.Created => log.debug("successfully stored refresh token")
                    case x => log.warn(s"failed to store refresh token (status code ${x}): " + response.entity)
                  }
                }
                case Failure(error) => log.warn("failed to store refresh token: " + error.getMessage)
                case _ => log.warn("failed to store refresh token due to unknown error")
              }
            }

            // redirect to the root url ("/"), with a fragment containing the user's original path and
            // the access token. The access token part LOOKS like a query param, but the entire string including "?"
            // is actually the fragment and the UI will parse it out.
            val uiRedirect = Uri()
              .withPath(Uri.Path./)
              .withFragment(s"${actualState}?access_token=${accessToken}")

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
