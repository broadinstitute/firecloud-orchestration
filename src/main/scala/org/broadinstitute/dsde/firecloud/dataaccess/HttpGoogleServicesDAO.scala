package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorRefFactory
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpResponseException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.cloudbilling.Cloudbilling
import com.google.api.services.cloudbilling.model.ProjectBillingInfo
import com.google.api.services.compute.ComputeScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.{Spreadsheet, SpreadsheetProperties, ValueRange}
import com.google.api.services.storage.{Storage, StorageScopes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impGoogleObjectMetadata
import org.broadinstitute.dsde.firecloud.model.{OAuthUser, ObjectMetadata, UserInfo}
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.httpx.UnsuccessfulResponseException
import spray.httpx.encoding.Gzip
import spray.json._
import spray.routing.RequestContext

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Result from Google's pricing calculator price list
  * (https://cloudpricingcalculator.appspot.com/static/data/pricelist.json).
  */
case class GooglePriceList(prices: GooglePrices, version: String, updated: String)

/** Partial price list. Attributes can be added as needed to import prices for more products. */
case class GooglePrices(cpBigstoreStorage: UsPriceItem,
                        cpComputeengineInternetEgressNA: UsTieredPriceItem)

/** Price item containing only US currency. */
case class UsPriceItem(us: BigDecimal)

/** Tiered price item containing only US currency.
  *
  * Used for egress, may need to be altered to work with other types in the future.
  * Contains a map of the different tiers of pricing, where the key is the size in GB
  * for that tier and the value is the cost in USD for that tier.
  */
case class UsTieredPriceItem(tiers: Map[Long, BigDecimal])

object GooglePriceListJsonProtocol extends DefaultJsonProtocol {
  implicit val UsPriceItemFormat = jsonFormat1(UsPriceItem)
  implicit object UsTieredPriceItemFormat extends RootJsonFormat[UsTieredPriceItem] {
    override def write(value: UsTieredPriceItem): JsValue = ???
    override def read(json: JsValue): UsTieredPriceItem = json match {
      case JsObject(values) => UsTieredPriceItem(values("tiers").asJsObject.fields.map{ case (name, value) => name.toLong -> BigDecimal(value.toString)})
      case x => throw new DeserializationException("invalid value: " + x)
    }
  }
  implicit val GooglePricesFormat = jsonFormat(GooglePrices, "CP-BIGSTORE-STORAGE", "CP-COMPUTEENGINE-INTERNET-EGRESS-NA-NA")
  implicit val GooglePriceListFormat = jsonFormat(GooglePriceList, "gcp_price_list", "version", "updated")
}
import org.broadinstitute.dsde.firecloud.dataaccess.GooglePriceListJsonProtocol._

object HttpGoogleServicesDAO extends GoogleServicesDAO with FireCloudRequestBuilding with LazyLogging {

  // application name to use within Google api libraries
  private final val appName = "firecloud:orchestration"

  // the minimal scopes needed to get through the auth proxy and populate our UserInfo model objects
  val authScopes = Seq("profile", "email")
  // the minimal scope to read from GCS
  val storageReadOnly = Seq(StorageScopes.DEVSTORAGE_READ_ONLY)
  // the scope we want is not defined in CloudbillingScopes, so we hardcode it here
  val billingScope = Seq("https://www.googleapis.com/auth/cloud-billing")

  val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  val jsonFactory = JacksonFactory.getDefaultInstance

  val pemFile = FireCloudConfig.Auth.pemFile
  val pemFileClientId = FireCloudConfig.Auth.pemFileClientId

  val rawlsPemFile = FireCloudConfig.Auth.rawlsPemFile
  val rawlsPemFileClientId = FireCloudConfig.Auth.rawlsPemFileClientId

  val trialBillingPemFile = FireCloudConfig.Auth.trialBillingPemFile
  val trialBillingPemFileClientId = FireCloudConfig.Auth.trialBillingPemFileClientId

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

  def getTrialBillingManagerCredential: Credential = {
    val builder = new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(trialBillingPemFileClientId)
      .setServiceAccountScopes(authScopes ++ billingScope)
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(trialBillingPemFile))

    builder.build()
  }

  def getTrialBillingManagerAccessToken = {
    val googleCredential = getTrialBillingManagerCredential
    googleCredential.refreshToken()
    googleCredential.getAccessToken
  }

  def getTrialBillingManagerEmail = trialBillingPemFileClientId

  def getCloudBillingManager(credential: Credential): Cloudbilling = {
    new Cloudbilling.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
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
    val storage = new Storage.Builder(httpTransport, jsonFactory, getBucketServiceAccountCredential).setApplicationName(appName).build()
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

  def getObjectMetadata(bucketName: String, objectKey: String, authToken: String)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata] = {
    val metadataRequest = Get( getObjectResourceUrl(bucketName, objectKey) )
    val metadataPipeline = addCredentials(OAuth2BearerToken(authToken)) ~> sendReceive ~> unmarshal[ObjectMetadata]
    val request = metadataPipeline{metadataRequest}

    request.recover {
      case t: UnsuccessfulResponseException =>
        throw new FireCloudExceptionWithErrorReport(FCErrorReport(t.response))
    }
  }

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
          import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOAuthUser
          import spray.json._
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
                  logger.info(s"$userStr download via proxy allowed for [$objectStr]")
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
                        logger.info(s"$userStr download via signed URL allowed for [$objectStr]")
                        requestContext.redirect(getSignedUrl(bucketName, objectKey), StatusCodes.TemporaryRedirect)
                      case _ =>
                        // the service account cannot read the object, even though the user can. We cannot
                        // make a signed url, because the service account won't have permission to sign it.
                        // therefore, we rely on a direct link. We accept that a direct link is vulnerable to
                        // identity problems if the current user is signed in to multiple google identies in
                        // the same browser profile, but this is the best we can do.
                        // generate direct link per https://cloud.google.com/storage/docs/authentication#cookieauth
                        logger.info(s"$userStr download via direct link allowed for [$objectStr]")
                        requestContext.redirect(getDirectDownloadUrl(bucketName, objectKey), StatusCodes.TemporaryRedirect)
                    }
                  }
                }

              case _ =>
                // the user does not have access to the object.
                val responseStr = objectResponse.entity.asString.replaceAll("\n","")
                logger.warn(s"$userStr download denied for [$objectStr], because (${objectResponse.status}): $responseStr")
                requestContext.complete(objectResponse)
            }
          }
        case _ =>
          // Google did not return a profile for this user; abort. Reloading will resolve the issue if it's caused by an expired token.
          logger.warn(s"Unknown user attempted download for [$objectStr] and was denied. User info (${userResponse.status}): ${userResponse.entity.asString}")
          requestContext.complete(Unauthorized, "There was a problem authorizing your download. Please reload FireCloud and try again.")
      }
    }
  }

  /** Fetch the latest price list from Google. Returns only the subset of prices that we find we have use for. */
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    val pipeline: HttpRequest => Future[GooglePriceList] = sendReceive ~> decode(Gzip) ~> unmarshal[GooglePriceList]
    pipeline(Get(FireCloudConfig.GoogleCloud.priceListUrl))
  }

  /**
    * Saves a google drive spreadsheet with the provided SpreadsheetProperties object.
    * Note that this does not *populate* the spreadsheet, just creates it with the provided properties
    * Call GoogleServicesDAO#updateSpreadsheet to add data once a spreadsheet is created.
    *
    * @param requestContext RequestContext
    * @param userInfo       UserInfo
    * @param props          SpreadsheetProperties
    * @return               JsObject representing the Google Create response
    */
  def createSpreadsheet(requestContext: RequestContext, userInfo: UserInfo, props: SpreadsheetProperties): JsObject = {
    val credential = new GoogleCredential().setAccessToken(userInfo.accessToken.token)
    val service = new Sheets.Builder(httpTransport, jsonFactory, credential).setApplicationName("FireCloud").build()
    val spreadsheet = new Spreadsheet().setProperties(props)
    val response = service.spreadsheets().create(spreadsheet).execute()
    response.toString.parseJson.asJsObject
  }

  /**
    * Updates an existing google drive spreadsheet with the provided data.
    *
    * @param requestContext RequestContext
    * @param userInfo       UserInfo
    * @param spreadsheetId  Spreadsheet ID
    * @param newContent     ValueRange
    * @return               JsObject representing the Google Update response
    */
  def updateSpreadsheet(requestContext: RequestContext, userInfo: UserInfo, spreadsheetId: String, newContent: ValueRange): JsObject = {
    val credential = new GoogleCredential().setAccessToken(userInfo.accessToken.token)
    val service = new Sheets.Builder(httpTransport, jsonFactory, credential).setApplicationName("FireCloud").build()

    // Retrieve existing records
    val getExisting: Sheets#Spreadsheets#Values#Get = service.spreadsheets().values.get(spreadsheetId, "Sheet1")
    val existingContent: ValueRange = getExisting.execute()

    // Smart merge existing with new
    val rows = updatePreservingOrder(newContent, existingContent)

    // Send update
    val response = service.spreadsheets().values().update(spreadsheetId, newContent.getRange, newContent.setValues(rows)).setValueInputOption("RAW").execute()
    response.toString.parseJson.asJsObject
  }

  private def updatePreservingOrder(newContent: ValueRange, existingContent: ValueRange): List[java.util.List[AnyRef]] = {
    val header: java.util.List[AnyRef] = existingContent.getValues.get(0)
    val existingRecords: List[java.util.List[AnyRef]] = existingContent.getValues.drop(1).toList

    val newRecords: List[java.util.List[AnyRef]] = newContent.getValues.drop(1).toList

    // Go through existing records and update them in place
    val existingRecordsUpdated = existingRecords.map { existingRecord =>
      val matchingNewRecord = newRecords.find { newRecordCandidate =>
        newRecordCandidate.get(0) == existingRecord.get(0)
      }

      matchingNewRecord match {
        case Some(newRecord) => newRecord // Overwrite the entire row in place, adequate unless we want to preserve user-added columns
        case _ => existingRecord // Don't delete existing records that aren't in the new export
      }
    }

    // Create new records for newly-added projects
    val recordsToAppend = newRecords.filter { newRecordCandidate =>
      !existingRecords.exists { existingRecordCandidate =>
        existingRecordCandidate.get(0) == newRecordCandidate.get(0)
      }
    }

    List(header) ++ existingRecordsUpdated ++ recordsToAppend
  }

  override def trialBillingManagerRemoveBillingAccount(project: String, targetUserEmail: String): Boolean = {
    val projectName = s"projects/$project" // format needed by Google

    // as the service account, get the current billing info to make sure we are removing the right thing.
    // this call will fail if the free-tier billing account has already been removed from the project.
    val billingService = getCloudBillingManager(getTrialBillingManagerCredential)

    val readRequest = billingService.projects().getBillingInfo(projectName)
    Try(executeGoogleRequest[ProjectBillingInfo](readRequest)) match {
      case Failure(f) =>
        logger.warn(s"Could not read billing info for free trial project $project at termination. This " +
          "probably means the user has already swapped billing, but you may want to doublecheck.")
        true
      case Success(currentBillingInfo) =>
        if (currentBillingInfo.getBillingAccountName != FireCloudConfig.Trial.billingAccount) {
          // the project is not linked to the free-tier billing account. Don't change anything.
          logger.warn(s"Free trial project $project has third-party billing account " +
            s"${currentBillingInfo.getBillingAccountName}; not removing it.")
          true
        } else {
          // At this point, we know that the user is a member of the project and that the project
          // is on the free-trial billing account. We've done our due diligence - remove the billing.
          // We do this by creating a ProjectBillingInfo with an empty account name - that's how Google
          // indicates we want to remove the billing account association.
          val noBillingInfo = new ProjectBillingInfo().setBillingAccountName("")
          // generate the request to send to Google
          val noBillingRequest = getCloudBillingManager(getTrialBillingManagerCredential)
            .projects().updateBillingInfo(projectName, noBillingInfo)
          // send the request
          val updatedProject = executeGoogleRequest[ProjectBillingInfo](noBillingRequest)
          updatedProject.getBillingEnabled != null && updatedProject.getBillingEnabled
        }
    }
  }

  // ====================================================================================
  // following two methods borrowed from rawls. I'd much prefer to just import workbench-google
  // from workbench-libs, but that has spray vs. akka-http conflicts. So this will do for now.
  // ====================================================================================
  protected def executeGoogleRequest[T](request: AbstractGoogleClientRequest[T]): T = {
    executeGoogleCall(request) { response =>
      response.parseAs(request.getResponseClass)
    }
  }
  protected def executeGoogleCall[A,B](request: AbstractGoogleClientRequest[A])(processResponse: (com.google.api.client.http.HttpResponse) => B): B = {
    Try {
      request.executeUnparsed()
    } match {
      case Success(response) =>
        try {
          processResponse(response)
        } finally {
          response.disconnect()
        }
      case Failure(httpRegrets: HttpResponseException) =>
        throw httpRegrets
      case Failure(regrets) =>
        throw regrets
    }
  }
  // ====================================================================================
  // END methods borrowed from rawls
  // ====================================================================================

  def status: Future[SubsystemStatus] = {
    val storage = new Storage.Builder(httpTransport, jsonFactory, getBucketServiceAccountCredential).setApplicationName(appName).build()
    val bucketResponseTry = Try(storage.buckets().list(FireCloudConfig.FireCloud.serviceProject).executeUsingHead())
    bucketResponseTry match {
      case scala.util.Success(bucketResponse) => bucketResponse.getStatusCode match {
        case x if x == 200 => Future(SubsystemStatus(ok = true, messages = None))
        case _ => Future(SubsystemStatus(ok = false, messages = Some(List(bucketResponse.parseAsString()))))
      }
      case Failure(ex) => Future(SubsystemStatus(ok = false, messages = Some(List(ex.getMessage))))
    }
  }

}
