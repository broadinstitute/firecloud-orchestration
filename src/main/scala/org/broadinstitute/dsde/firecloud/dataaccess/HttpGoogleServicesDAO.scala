package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.StringReader

import akka.actor.ActorRefFactory
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeFlow, GoogleClientSecrets, GoogleCredential}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.storage.{Storage, StorageScopes}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{OAuthException, OAuthTokens, OAuthUser, ObjectMetadata}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impGoogleObjectMetadata
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.encoding.Gzip
import spray.json._
import spray.routing.RequestContext

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Result from Google's pricing calculator price list
  * (https://cloudpricingcalculator.appspot.com/static/data/pricelist.json).
  */
case class GooglePriceList(prices: GooglePrices, version: String, updated: String)

/** Partial price list. Attributes can be added as needed to import prices for more products. */
case class GooglePrices(cpBigstoreStorage: UsPriceItem)

/** Price item containing only US currency. */
case class UsPriceItem(us: BigDecimal)

object GooglePriceListJsonProtocol extends DefaultJsonProtocol {
  implicit val UsPriceItemFormat = jsonFormat1(UsPriceItem)
  implicit val GooglePricesFormat = jsonFormat(GooglePrices, "CP-BIGSTORE-STORAGE")
  implicit val GooglePriceListFormat = jsonFormat(GooglePriceList, "gcp_price_list", "version", "updated")
}
import org.broadinstitute.dsde.firecloud.dataaccess.GooglePriceListJsonProtocol._

object HttpGoogleServicesDAO extends FireCloudRequestBuilding {

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

  def getRawlsServiceAccountAccessToken = {
    val googleCredential = new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(rawlsPemFileClientId)
      .setServiceAccountScopes(storageReadOnly)
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(rawlsPemFile))
      .build()

    googleCredential.refreshToken()
    googleCredential.getAccessToken
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
    val expireSeconds = (System.currentTimeMillis() / 1000) + 120 // expires 2 minutes (120 seconds) from now
    val objectPath = s"/$bucketName/$objectKey"

    val signableString = s"$verb\n$md5\n$contentType\n$expireSeconds\n$objectPath"

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
      s"&Expires=$expireSeconds" +
      "&Signature=" + java.net.URLEncoder.encode(java.util.Base64.getEncoder.encodeToString(signedBytes), "UTF-8")
  }

  def getDirectDownloadUrl(bucketName: String, objectKey: String) = s"https://storage.cloud.google.com/$bucketName/$objectKey"

  def getObjectResourceUrl(bucketName: String, objectKey: String) = {
    val gcsStatUrl = "https://www.googleapis.com/storage/v1/b/%s/o/%s"
    gcsStatUrl.format(bucketName, java.net.URLEncoder.encode(objectKey,"UTF-8"))
  }

  def objectAccessCheck(bucketName: String, objectKey: String, authToken: String)
                       (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = {
    val accessRequest = Get( HttpGoogleServicesDAO.getObjectResourceUrl(bucketName, objectKey) )
    val accessPipeline = addCredentials(OAuth2BearerToken(authToken)) ~> sendReceive
    accessPipeline{accessRequest}
  }

  def getUserProfile(requestContext: RequestContext)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = {
    val profileRequest = Get( "https://www.googleapis.com/oauth2/v3/userinfo" )
    val profilePipeline = authHeaders(requestContext) ~> sendReceive

    profilePipeline{profileRequest}
  }

  // download "proxy" for GCS objects. When using a simple RESTful url to download from GCS, Chrome/GCS will look
  // at all the currently-signed in Google identities for the browser, and pick the "most recent" one. This may
  // not be the one we want to use for downloading the GCS object. To force the identity we want, we jump through
  // some hoops: if we can, we presign a url using a service account.
  // pseudocode:
  //  if (we can determine the user's identity via google)
  //    if (the user has access to the object)
  //      if (the service account has access to the object)
  //        redirect to a signed url that guarantees the user's identity
  //      else
  //        redirect to a direct download in GCS
  def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext) = {

    val objectStr = s"gs://$bucketName/$objectKey" // for logging
    // can we determine the current user's identity with Google?
    getUserProfile(requestContext) map { userResponse =>
      userResponse.status match {
        case OK =>
          // user is known to Google. Extract the user's email and SID from the response, for logging
          import spray.json._
          import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOAuthUser
          val oauthUser:Try[OAuthUser] = Try(userResponse.entity.asString.parseJson.convertTo[OAuthUser])
          val userStr = (oauthUser getOrElse userResponse.entity).toString
          // Does the user have access to the target file?
          objectAccessCheck(bucketName, objectKey, userAuthToken) map { objectResponse =>
            objectResponse.status match {
              case OK =>
                // user has access to the object.
                // switch solutions based on the size of the target object. If the target object is small enough,
                // proxy it through orchestration; this allows embedded images inside HTML reports to render correctly.
                val objSize:Int = Try(objectResponse.entity.asString.parseJson.convertTo[ObjectMetadata].size)
                                                    .toOption.getOrElse("-1").toInt
                // 8MB or under ...
                if (objSize > 0 && objSize < 8388608) {
                  log.info(s"$userStr download via proxy allowed for [$objectStr]")
                  val gcsApiUrl = getObjectResourceUrl(bucketName, objectKey) + "?alt=media"
                  val extReq = Get(gcsApiUrl)
                  val proxyPipeline = addCredentials(OAuth2BearerToken(userAuthToken)) ~> sendReceive
                  // ensure we set the content-type correctly when proxying
                  proxyPipeline(extReq) map { proxyResponse =>
                      proxyResponse.header[HttpHeaders.`Content-Type`] match {
                      case Some(ct) =>requestContext.withHttpResponseEntityMapped(e => HttpEntity(ct.contentType, e.data)).complete(proxyResponse.status, proxyResponse.entity)
                      case None => requestContext.complete(proxyResponse.status, proxyResponse.entity)
                    }
                  }
                } else {
                  // object is too large to proxy; try to make a signed url.
                  // now make a final request to see if our service account has access, so it can sign a URL
                  objectAccessCheck(bucketName, objectKey, getRawlsServiceAccountAccessToken) map { serviceAccountResponse =>
                    serviceAccountResponse.status match {
                      case OK =>
                        // the service account can read the object too. We are safe to sign a url.
                        log.info(s"$userStr download via signed URL allowed for [$objectStr]")
                        requestContext.redirect(getSignedUrl(bucketName, objectKey), StatusCodes.TemporaryRedirect)
                      case _ =>
                        // the service account cannot read the object, even though the user can. We cannot
                        // make a signed url, because the service account won't have permission to sign it.
                        // therefore, we rely on a direct link. We accept that a direct link is vulnerable to
                        // identity problems if the current user is signed in to multiple google identies in
                        // the same browser profile, but this is the best we can do.
                        // generate direct link per https://cloud.google.com/storage/docs/authentication#cookieauth
                        log.info(s"$userStr download via direct link allowed for [$objectStr]")
                        requestContext.redirect(getDirectDownloadUrl(bucketName, objectKey), StatusCodes.TemporaryRedirect)
                    }
                  }
                }

              case _ =>
                // the user does not have access to the object.
                val responseStr = objectResponse.entity.asString.replaceAll("\n","")
                log.warn(s"$userStr download denied for [$objectStr], because (${objectResponse.status}): $responseStr")
                requestContext.complete(objectResponse)
            }
          }
        case _ =>
          // Google did not return a profile for this user; abort.
          log.warn(s"Unknown user attempted download for [$objectStr] and was denied. User info (${userResponse.status}): ${userResponse.entity.asString}")
          requestContext.complete(userResponse)
      }
    }
  }

  /** Fetch the latest price list from Google. Returns only the subset of prices that we find we have use for. */
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    val pipeline: HttpRequest => Future[HttpResponse] = sendReceive ~> decode(Gzip)
    val response: Future[HttpResponse] = pipeline(Get(FireCloudConfig.GoogleCloud.priceListUrl))
    response map { r => r.entity.asString.parseJson.convertTo[GooglePriceList] }
  }
}
