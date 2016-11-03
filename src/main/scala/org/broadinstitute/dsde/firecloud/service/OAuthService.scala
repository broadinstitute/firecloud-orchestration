package org.broadinstitute.dsde.firecloud.service

import java.io.StringReader

import akka.actor.{Actor, Props}
import akka.pattern._
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeFlow, GoogleClientSecrets}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.storage.StorageScopes
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.core.{ProfileClient, ProfileClientActor}
import org.broadinstitute.dsde.firecloud.model.{RawlsToken, RawlsTokenDate, UserInfo}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.OAuthService.HandleOauthCode
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

// see https://developers.google.com/identity/protocols/OAuth2WebServer

object OAuthService {
  val remoteTokenPutPath = FireCloudConfig.Rawls.authPrefix + "/user/refreshToken"
  val remoteTokenPutUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenPutPath

  val remoteTokenDatePath = FireCloudConfig.Rawls.authPrefix + "/user/refreshTokenDate"
  val remoteTokenDateUrl = FireCloudConfig.Rawls.baseUrl + remoteTokenDatePath

  sealed trait OauthServiceMessage
  case class HandleOauthCode(code: String, redirectUri: String) extends OauthServiceMessage
  case class GetRefreshTokenStatus() extends OauthServiceMessage

  def props(oauthService: () => OAuthService): Props = {
    Props(oauthService())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new OAuthService()
}

class OAuthService()(implicit protected val executionContext: ExecutionContext) extends Actor
  with LazyLogging {

  lazy val log = LoggerFactory.getLogger(getClass)


  override def receive = {
    case HandleOauthCode(code: String, redirectUri: String) => {
      handleOauthCode(code, redirectUri)
    } pipeTo sender
  }

  def handleOauthCode(code: String, redirectUri: String): Future[PerRequestMessage] = {
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
      updateNihStatus(idToken.getPayload.getSubject, accessToken)
      refreshToken match {
        case Some(x) => completeWithRefreshToken(accessToken, x)
        case None => Future(RequestComplete(StatusCodes.NoContent))
      }
    } catch {
      case e: TokenResponseException =>
        Future(RequestComplete(HttpResponse(StatusCodes.BadRequest, e.getContent)))
    }
  }

  def getRefreshTokenStatus(): Future[PerRequestMessage] = {
    val pipeline = addCredentials(userInfo.accessToken) ~> sendReceive
    val tokenDateReq = Get(OAuthService.remoteTokenDateUrl)
    val tokenDateFuture: Future[HttpResponse] = pipeline {
      tokenDateReq
    }
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
                RequestComplete(StatusCodes.NoContent)
              case x =>
                log.info(s"User's refresh token is $x days old; requesting a new one.")
                RequestComplete(StatusCodes.OK, Map("requiresRefresh" -> true))
            }
          case StatusCodes.BadRequest =>
            log.info(s"User has an illegal refresh token; requesting a new one.")
            // rawls has a bad refresh token, restart auth.
            RequestComplete(StatusCodes.OK, Map("requiresRefresh" -> true))
          case StatusCodes.NotFound =>
            log.info(s"User does not already have a refresh token; requesting a new one.")
            // rawls does not have a refresh token for us. restart auth.
            RequestComplete(StatusCodes.OK, Map("requiresRefresh" -> true))
          case x =>
            log.warn("Unexpected response code when querying rawls for existence of refresh token: "
              + x.value + " " + x.reason)
            RequestComplete(
              StatusCodes.InternalServerError,
              Map("error" -> Map("value" -> x.value, "reason" -> x.reason))
            )
        }
      case Failure(error) =>
        log.warn("Could not query rawls for existence of refresh token: " + error.getMessage)
        RequestComplete(
          StatusCodes.InternalServerError,
          Map("error" -> Map("message" -> error.getMessage))
        )
    }
    throw new RuntimeException("This code isn't written correctly.")
  }

  private def updateNihStatus(subjectId: String, accessToken: String) = {
    val userInfo = UserInfo("", OAuth2BearerToken(accessToken), -1, subjectId)
    val dummyRequestContext = RequestContext(HttpRequest(), null, Uri.Path.SingleSlash)
    perRequest(dummyRequestContext, Props(new ProfileClientActor(dummyRequestContext)),
      ProfileClient.GetAndUpdateNIHStatus(userInfo))
  }

  private def completeWithRefreshToken(accessToken: String, refreshToken: String): Future[PerRequestMessage] = {
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
    Future(RequestComplete(HttpResponse(StatusCodes.NoContent)))
  }
}
