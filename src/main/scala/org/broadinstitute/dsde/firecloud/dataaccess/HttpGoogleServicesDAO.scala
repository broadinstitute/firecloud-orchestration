package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.StringReader

import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeFlow, GoogleClientSecrets, GoogleTokenResponse}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{OAuthException, OAuthTokens}
import spray.http.Uri

import scala.collection.JavaConversions._

object HttpGoogleServicesDAO {

  val secretContent = FireCloudConfig.Auth.googleSecretJson
  val baseUrl = FireCloudConfig.FireCloud.baseUrl
  val callbackPath = "/service/oauth2callback"

  // this needs to match a value in the "Authorized redirect URIs" section of the Google credential in use
  val callbackUri = Uri(s"${baseUrl}${callbackPath}")

  // modify these if we need more granular access in the future
  val gcsFullControl = "https://www.googleapis.com/auth/devstorage.full_control"
  val computeFullControl = "https://www.googleapis.com/auth/compute"
  // TODO: remove compute?
  val scopes = Seq(gcsFullControl,computeFullControl,"profile","email")

  val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  val jsonFactory = JacksonFactory.getDefaultInstance

  val clientSecrets = GoogleClientSecrets.load(jsonFactory, new StringReader(secretContent))

  val flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport,
    jsonFactory, clientSecrets, scopes).build()


  /**
   * first step of OAuth dance: redirect the browser to Google's login page
   */
  def getGoogleRedirectURI(state: String, approvalPrompt: String = "auto"): String = {
    flow.newAuthorizationUrl()
        .setRedirectUri(callbackUri.toString)
        .setState(state)
        .setAccessType("offline")   // enables refresh token
        .setApprovalPrompt(approvalPrompt) // "force" to get a new refresh token
        .build()
        // TODO: login hint?
  }

  /**
   * third step of OAuth dance: exchange an auth code for access/refresh tokens
   */
  def getTokens(actualState: String,  expectedState: String, authCode: String): OAuthTokens = {

    if ( actualState != expectedState ) throw new OAuthException(
      "State mismatch: this authentication request cannot be completed.")

    val gcsTokenResponse: GoogleTokenResponse = flow.newTokenRequest(authCode)
      .setRedirectUri(callbackUri.toString)
      .execute()

    val refreshToken = gcsTokenResponse.getRefreshToken match {
      case null => None
      case x => Some(x)
    }
    val idToken = gcsTokenResponse.getIdToken match {
      case null => None
      case x => Some(x)
    }

    new OAuthTokens(
      gcsTokenResponse.getAccessToken,
      gcsTokenResponse.getTokenType,
      gcsTokenResponse.getExpiresInSeconds,
      refreshToken,
      idToken
    )
  }

  def randomStateString = randomString(24)

  def randomString(length: Int) = scala.util.Random.alphanumeric.take(length).mkString

}
