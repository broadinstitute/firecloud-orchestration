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
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.OAuthService.{GetRefreshTokenStatus, HandleOauthCode}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.joda.time.{DateTime, Days}
import org.slf4j.LoggerFactory
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

// see https://developers.google.com/identity/protocols/OAuth2WebServer

object OAuthService {
  sealed trait OauthServiceMessage
  case class HandleOauthCode(code: String, redirectUri: String) extends OauthServiceMessage
  case class GetRefreshTokenStatus(userInfo: UserInfo) extends OauthServiceMessage

  def props(oauthService: () => OAuthService): Props = {
    Props(oauthService())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new OAuthService(app.rawlsDAO, app.thurloeDAO)
}

class OAuthService(val rawlsDao: RawlsDAO, val thurloeDao: ThurloeDAO)
  (implicit protected val executionContext: ExecutionContext) extends Actor
  with LazyLogging {

  lazy val log = LoggerFactory.getLogger(getClass)


  override def receive = {
    case HandleOauthCode(code, redirectUri) => handleOauthCode(code, redirectUri) pipeTo sender
    case GetRefreshTokenStatus(userInfo) => getRefreshTokenStatus(userInfo) pipeTo sender
  }

  private def handleOauthCode(code: String, redirectUri: String): Future[PerRequestMessage] = {
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
      val userInfo = UserInfo("", OAuth2BearerToken(accessToken), -1, idToken.getPayload.getSubject)
      thurloeDao.getProfile(userInfo) map {
        _.map { thurloeDao.maybeUpdateNihLinkExpiration(userInfo, _) }
      }
      refreshToken match {
        case Some(x) =>
          rawlsDao.saveRefreshToken(accessToken, x) map { _ => RequestComplete(StatusCodes.NoContent)}
        case None => Future(RequestComplete(StatusCodes.NoContent))
      }
    } catch {
      case e: TokenResponseException =>
        Future(RequestComplete(HttpResponse(StatusCodes.BadRequest, e.getContent)))
    }
  }

  private def getRefreshTokenStatus(userInfo: UserInfo): Future[PerRequestMessage] = {
    rawlsDao.getRefreshTokenStatus(userInfo) map {
      case Some(tokenDate) =>
        val ageDaysCount = Days.daysBetween(tokenDate, DateTime.now).getDays
        ageDaysCount match {
          case x if x < 90 =>
            log.debug(s"User's refresh token is $x days old; all good!")
            RequestComplete(StatusCodes.NoContent)
          case x =>
            log.info(s"User's refresh token is $x days old; requesting a new one.")
            RequestComplete(StatusCodes.OK, Map("requiresRefresh" -> true))
        }
      case None =>
        RequestComplete(StatusCodes.OK, Map("requiresRefresh" -> true))
    }
  }
}
