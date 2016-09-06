package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.StringReader

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeFlow, GoogleClientSecrets, GoogleCredential}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.storage.{Storage, StorageScopes}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{OAuthException, OAuthTokens}
import org.slf4j.LoggerFactory
import spray.http.Uri

import scala.collection.JavaConversions._

object HttpGoogleServicesDAO {

  val baseUrl = FireCloudConfig.FireCloud.baseUrl
  val callbackPath = "/callback"

  // this needs to match a value in the "Authorized redirect URIs" section of the Google credential in use
  val callbackUri = Uri(s"${baseUrl}${callbackPath}")

  // the minimal scopes needed to get through the auth proxy and populate our UserInfo model objects
  val authScopes = Seq("profile", "email")
  // the minimal scope to read from GCS
  val storageReadOnly = Seq(StorageScopes.DEVSTORAGE_READ_ONLY)
  // the scopes we request for the end user during interactive login. TODO: remove compute?
  val userLoginScopes = Seq(StorageScopes.DEVSTORAGE_FULL_CONTROL, ComputeScopes.COMPUTE) ++ authScopes

  val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  val jsonFactory = JacksonFactory.getDefaultInstance

  val clientSecrets = GoogleClientSecrets.load(jsonFactory, new StringReader(FireCloudConfig.Auth.googleSecretJson))

  // Google Java Client doesn't offer direct access to the allowed origins, so we have to jump through a couple hoops
  val origins:List[String] = (clientSecrets.getDetails.get("javascript_origins").asInstanceOf[java.util.ArrayList[String]]).toList

  val flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport,
    jsonFactory, clientSecrets, userLoginScopes).build()

  val pemFile = FireCloudConfig.Auth.pemFile
  val pemFileClientId = FireCloudConfig.Auth.pemFileClientId

  val rawlsPemFile = FireCloudConfig.Auth.rawlsPemFile
  val rawlsPemFileClientId = FireCloudConfig.Auth.rawlsPemFileClientId

  lazy val log = LoggerFactory.getLogger(getClass)

  /**
   * first step of OAuth dance: redirect the browser to Google's login page
   */
  def getGoogleRedirectURI(state: String, approvalPrompt: String = "auto", overrideScopes: Option[Seq[String]] = None): String = {
    val urlBuilder = flow.newAuthorizationUrl()
      .setRedirectUri(callbackUri.toString)
      .setState(state)
      .setAccessType("offline")   // enables refresh token
      .setApprovalPrompt(approvalPrompt) // "force" to get a new refresh token

    overrideScopes match {
      case Some(newScopes) => urlBuilder.setScopes(newScopes).build()
      case _ => urlBuilder.build()
    }

    // TODO: login hint?
  }

  /**
   * third step of OAuth dance: exchange an auth code for access/refresh tokens
   */
  def getTokens(actualState: String,  expectedState: String, authCode: String): OAuthTokens = {

    if ( actualState != expectedState ) throw new OAuthException(
      "State mismatch: this authentication request cannot be completed.")

    val gcsTokenResponse = flow.newTokenRequest(authCode)
      .setRedirectUri(callbackUri.toString)
      .execute()

    OAuthTokens(gcsTokenResponse)
  }

  // check the requested UI redirect against the list of allowed JS origins
  def whitelistRedirect(userUri:String) = {
    userUri match {
      case "" => ""
      case x if origins.contains(x) => x
      case _ =>
        log.warn("User requested a redirect to " + userUri + ", but that url does not exist in whitelist.")
        ""
    }
  }

  def randomStateString = randomString(24)

  def randomString(length: Int) = scala.util.Random.alphanumeric.take(length).mkString

  def getAdminUserAccessToken = {
    val googleCredential = new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(pemFileClientId)
      .setServiceAccountScopes(authScopes) // use the smallest scope possible
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(pemFile))
      .build()

    googleCredential.refreshToken()
    googleCredential.getAccessToken
  }

  private def getBucketServiceAccountCredential: Credential = {
    new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(pemFileClientId)
      .setServiceAccountScopes(storageReadOnly)
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(pemFile))
      .build()
  }

  def getBucketObjectAsInputStream(bucketName: String, objectKey: String) = {
    val storage = new Storage.Builder(httpTransport, jsonFactory, getBucketServiceAccountCredential).setApplicationName("firecloud").build()
    storage.objects().get(bucketName, objectKey).executeMediaAsInputStream
  }

  // create a GCS signed url as per https://cloud.google.com/storage/docs/access-control/create-signed-urls-program
  def getSignedUrl(bucketName: String, objectKey: String) = {

    // generate the string-to-be-signed
    val verb = "GET"
    val md5 = ""
    val contentType = ""
    val expire = (System.currentTimeMillis() /1000) + 300 // expires 5 minutes (300 seconds) from now
    val objectPath = s"/$bucketName/$objectKey"

    val signableString = s"$verb\n$md5\n$contentType\n$expire\n$objectPath"

    // use GoogleCredential.Builder to parse the private key from the pem file
    val builder = new GoogleCredential.Builder()
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(rawlsPemFile))
    val privateKey = builder.getServiceAccountPrivateKey

    // sign the string
    val signature = java.security.Signature.getInstance("SHA256withRSA")
    signature.initSign(privateKey)
    signature.update(signableString.getBytes("UTF-8"))
    val signedBytes = signature.sign()

    // assemble the final url
    s"https://storage.googleapis.com/$bucketName/$objectKey" +
      s"?GoogleAccessId=$rawlsPemFileClientId" +
      s"&Expires=$expire" +
      "&Signature=" + java.net.URLEncoder.encode(java.util.Base64.getEncoder.encodeToString(signedBytes), "UTF-8")
  }

}
