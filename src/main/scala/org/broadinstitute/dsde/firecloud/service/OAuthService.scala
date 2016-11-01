package org.broadinstitute.dsde.firecloud.service

import java.io.StringReader

import akka.actor.Props
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeFlow, GoogleClientSecrets}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.storage.StorageScopes
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{ProfileClient, ProfileClientActor}
import org.broadinstitute.dsde.firecloud.model.{RawlsToken, RawlsTokenDate, UserInfo}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.joda.time.{DateTime, Days}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsonParser.ParsingException
import spray.json._
import spray.routing._

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.util.{Failure, Success}

// see https://developers.google.com/identity/protocols/OAuth2WebServer

object OAuthService {
  val remoteTokenPutPath = FireCloudConfig.Rawls.authPrefix + "/user/refreshToken"
  val remoteTokenPutUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenPutPath

  val remoteTokenDatePath = FireCloudConfig.Rawls.authPrefix + "/user/refreshTokenDate"
  val remoteTokenDateUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenDatePath

}

trait OAuthService extends HttpService with PerRequestCreator with FireCloudDirectives with FireCloudRequestBuilding with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    path("handle-oauth-code") {
      post { requestContext =>
        try {
          val params = requestContext.request.entity.data.asString.parseJson.asJsObject
          if (!params.fields.isDefinedAt("code")) {
            requestContext.complete(
              StatusCodes.BadRequest,
              Map("error" -> Map("summary" -> "Missing required key 'code'"))
            )
          }
          else if (!params.fields.isDefinedAt("redirectUri")) {
            requestContext.complete(
              StatusCodes.BadRequest,
              Map("error" -> Map("summary" -> "Missing required key 'redirectUri'"))
            )
          } else {
            try {
              val code = params.fields("code").convertTo[String]
              try {
                val redirectUri = params.fields("redirectUri").convertTo[String]
                val httpTransport = GoogleNetHttpTransport.newTrustedTransport
                val jsonFactory = JacksonFactory.getDefaultInstance
                val clientSecrets = GoogleClientSecrets.load(
                  jsonFactory, new StringReader(FireCloudConfig.Auth.googleSecretJson))
                var authScopes = Seq("profile", "email")
                authScopes ++= Seq(StorageScopes.DEVSTORAGE_FULL_CONTROL, ComputeScopes.COMPUTE)
                val flow = new GoogleAuthorizationCodeFlow.Builder(
                  httpTransport, jsonFactory, clientSecrets, authScopes
                ).build
                val request = flow.newTokenRequest(code)
                request.setRedirectUri(redirectUri)
                try {
                  val response = request.execute
                  val idToken = response.parseIdToken()
                  val accessToken = response.getAccessToken
                  val refreshToken = Option(response.getRefreshToken)
                  val sub = idToken.getPayload.getSubject
                  updateNihStatus(requestContext, sub, accessToken)
                  refreshToken match {
                    case Some(x) => completeWithRefreshToken(requestContext, accessToken, x)
                    case None => requestContext.complete(StatusCodes.NoContent)
                  }
                } catch {
                  case e: TokenResponseException =>
                    requestContext.complete(HttpResponse(StatusCodes.BadRequest, e.getContent))
                }
              } catch {
                case e: DeserializationException =>
                  requestContext.complete(
                    StatusCodes.BadRequest,
                    Map("error" -> Map("summary" -> "Invalid value for 'redirectUri'", "detail" -> e.msg))
                  )
              }
            } catch {
              case e: DeserializationException =>
                requestContext.complete(
                  StatusCodes.BadRequest,
                  Map("error" -> Map("summary" -> "Invalid value for 'code'", "detail" -> e.msg))
                )
            }
          }
        } catch {
          case e: ParsingException =>
            requestContext.complete(
              StatusCodes.BadRequest,
              Map("error" -> Map("summary" -> e.summary))
            )
        }
      }
    } ~
    path("api" / "refresh-token-status") {
      get {
        requireUserInfo() { userInfo => rc =>
          val pipeline = addCredentials(userInfo.accessToken) ~> sendReceive
          val tokenDateReq = Get(OAuthService.remoteTokenDateUrl)
          val tokenDateFuture: Future[HttpResponse] = pipeline { tokenDateReq }
          tokenDateFuture onComplete {
            case Success(response) =>
              response.status match {
                case StatusCodes.OK =>
                  // rawls found a refresh token; check its date
                  val tokenDate = unmarshal[RawlsTokenDate].apply(response)
                  val howOld = Days.daysBetween(
                    DateTime.parse(tokenDate.refreshTokenUpdatedDate), DateTime.now)
                  howOld.getDays match {
                    case x if x < 90 =>
                      log.debug(s"User's refresh token is $x days old; all good!")
                      rc.complete(StatusCodes.NoContent) // fine; continue on
                    case x =>
                      log.info(s"User's refresh token is $x days old; requesting a new one.")
                      rc.complete(StatusCodes.OK, Map("requiresRefresh" -> true))
                  }
                case StatusCodes.BadRequest =>
                  log.info(s"User has an illegal refresh token; requesting a new one.")
                  // rawls has a bad refresh token, restart auth.
                  rc.complete(StatusCodes.OK, Map("requiresRefresh" -> true))
                case StatusCodes.NotFound =>
                  log.info(s"User does not already have a refresh token; requesting a new one.")
                  // rawls does not have a refresh token for us. restart auth.
                  rc.complete(StatusCodes.OK, Map("requiresRefresh" -> true))
                case x =>
                  log.warn("Unexpected response code when querying rawls for existence of refresh token: "
                    + x.value + " " + x.reason)
                  rc.complete(
                    StatusCodes.InternalServerError,
                    Map("error" -> Map("value" -> x.value, "reason" -> x.reason))
                  )
              }
            case Failure(error) =>
              log.warn("Could not query rawls for existence of refresh token: " + error.getMessage)
              rc.complete(
                StatusCodes.InternalServerError,
                Map("error" -> Map("message" -> error.getMessage))
              )
          }
        }
      }
    }

  private def updateNihStatus(rc: RequestContext, subjectId: String, accessToken: String)  = {
    val userInfo = UserInfo("", OAuth2BearerToken(accessToken), -1, subjectId)
    perRequest(rc, Props(new ProfileClientActor(rc)),
      ProfileClient.GetAndUpdateNIHStatus(userInfo))
  }

  private def completeWithRefreshToken(rc: RequestContext, accessToken: String, refreshToken: String) = {
    val pipeline = addCredentials(OAuth2BearerToken(accessToken)) ~> sendReceive
    val tokenReq = Put(OAuthService.remoteTokenPutUrl, RawlsToken(refreshToken))
    val tokenStoreFuture: Future[HttpResponse] = pipeline {
      tokenReq
    }

    // we intentionally don't gate the login process on storage of the refresh token. Token storage
    // will happen async. If token storage fails, we rely on underlying services to notice the
    // missing token and re-initiate the oauth grants.
    tokenStoreFuture onComplete {
      case Success(response) =>
        response.status match {
          case StatusCodes.Created => log.info("successfully stored refresh token")
          case x => log.warn(s"failed to store refresh token (status code $x): " + response.entity)
        }
      case Failure(error) => log.warn("failed to store refresh token: " + error.getMessage)
    }
    rc.complete(HttpResponse(StatusCodes.NoContent))
  }
}
