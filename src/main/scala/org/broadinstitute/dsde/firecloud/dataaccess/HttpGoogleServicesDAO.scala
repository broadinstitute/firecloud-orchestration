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
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.api.services.storage.{Storage, StorageScopes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impGoogleObjectMetadata
import org.broadinstitute.dsde.firecloud.model.{AccessToken, OAuthUser, ObjectMetadata, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete, RequestCompleteWithHeaders}
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
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
  val spreadsheetScopes = Seq(SheetsScopes.SPREADSHEETS)

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

  def getTrialBillingManagerCredential(addlScopes: Seq[String] = billingScope): Credential = {
    val builder = new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountId(trialBillingPemFileClientId)
      .setServiceAccountScopes(authScopes ++ addlScopes)
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(trialBillingPemFile))

    builder.build()
  }

  def getTrialBillingManagerAccessToken = {
    val googleCredential = getTrialBillingManagerCredential()
    googleCredential.refreshToken()
    googleCredential.getAccessToken
  }

  def getTrialSpreadsheetAccessToken = {
    val googleCredential = getTrialBillingManagerCredential(spreadsheetScopes)
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
  // TODO: should we sign as the user's pet??
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

    // xml api implementation
    val metadataRequest = Head( getXmlApiMetadataUrl(bucketName, objectKey) )
    val metadataPipeline = addCredentials(OAuth2BearerToken(authToken)) ~> sendReceive
    metadataPipeline{metadataRequest} map { resp: HttpResponse =>
      resp.status match {
        case OK => xmlApiResponseToObject(resp, bucketName, objectKey)
        case _ =>
          // TODO: how to get error messaging equivalent to json api?
          // hacky: translate spray status code to akka-http status code
          val code = akka.http.scaladsl.model.StatusCodes.getForKey(resp.status.intValue).getOrElse(
            akka.http.scaladsl.model.StatusCodes.custom(resp.status.intValue, resp.status.defaultMessage)
          )
          throw new FireCloudExceptionWithErrorReport(ErrorReport(code, code.defaultMessage))
      }
    } recover {
      case t: UnsuccessfulResponseException =>
        throw new FireCloudExceptionWithErrorReport(FCErrorReport(t.response))
    }

    /*
    // json api implementation
    val metadataRequest = Get( getObjectResourceUrl(bucketName, objectKey) )
    val metadataPipeline = addCredentials(OAuth2BearerToken(authToken)) ~> sendReceive ~> unmarshal[ObjectMetadata]
    val request = metadataPipeline{metadataRequest}

    request.recover {
      case t: UnsuccessfulResponseException =>
        throw new FireCloudExceptionWithErrorReport(FCErrorReport(t.response))
    }
    */
  }

  def getXmlApiMetadataUrl(bucketName: String, objectKey: String) = {
    val gcsStatUrl = "https://storage.googleapis.com/%s/%s"
    gcsStatUrl.format(bucketName, java.net.URLEncoder.encode(objectKey,"UTF-8"))
  }

  // TODO: move to an ObjectMetadata.apply method?
  // TODO: verify correctness of all fields below
  // TODO: are the missing mediaLink and timeCreated fields breaking changes?
  def xmlApiResponseToObject(response: HttpResponse, bucketName: String, objectKey: String): ObjectMetadata = {
    // crc32c and md5hash are both in x-goog-hash, which exists multiple times in the response headers.
    val crc32c:String = response.headers
      .find{h => h.lowercaseName.equals("x-goog-hash") && h.value.startsWith("crc32c=")}
      .map(_.value.replaceFirst("crc32c=",""))
        .getOrElse("")
    val md5Hash: Option[String] = response.headers
      .find{h => h.lowercaseName.equals("x-goog-hash") && h.value.startsWith("md5=")}
      .map(_.value.replaceFirst("md5=",""))

    val headerMap: Map[String, String] = response.headers.map { h =>
      h.lowercaseName -> h.value
    }.toMap

    // exists in xml api, same value as json api
    val generation: String = headerMap("x-goog-generation") // x-goog-generation
    val size: String = headerMap("x-goog-stored-content-length") // TODO: x-goog-stored-content-length vs. content-length
    val storageClass: String = headerMap("x-goog-storage-class") // x-goog-storage-class
    val updated: String = headerMap("last-modified") // last-modified
    val contentType: Option[String] = headerMap.get("content-type") // content-type

    // different value in json and xml apis
    val etag: String = headerMap("etag") // TODO: xml api returns a quoted string. Unquote?

    // not in response headers but can be calculated from request
    val bucket: String = bucketName
    val name: String = objectKey
    val id: String = s"$bucket/$name/$generation"

    // not present xml api, does exist in json api
    val mediaLink: String = "TODO" // TODO
    val timeCreated: String = "TODO" // TODO

    val contentDisposition: Option[String] = headerMap.get("content-disposition")
    val contentEncoding: Option[String] = headerMap.get("content-encoding") // TODO: x-goog-stored-content-encoding vs. content-encoding

    val estimatedCostUSD: Option[BigDecimal] = None // hardcoded to None; not part of the Google response

    ObjectMetadata(bucket,crc32c,etag,generation,id,md5Hash,mediaLink,name,size,storageClass,
          timeCreated,updated,contentDisposition,contentEncoding,contentType,estimatedCostUSD)


  }

  def getObjectResourceUrl(bucketName: String, objectKey: String) = {
    val gcsStatUrl = "https://www.googleapis.com/storage/v1/b/%s/o/%s"
    gcsStatUrl.format(bucketName, java.net.URLEncoder.encode(objectKey,"UTF-8"))
  }

  def objectAccessCheck(bucketName: String, objectKey: String, authToken: WithAccessToken)
                       (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = {
    val accessRequest = Head( HttpGoogleServicesDAO.getXmlApiMetadataUrl(bucketName, objectKey) )
    val accessPipeline = addCredentials(authToken.accessToken) ~> sendReceive
    accessPipeline{accessRequest}
  }

  def getUserProfile(accessToken: WithAccessToken)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = {
    val profileRequest = Get( "https://www.googleapis.com/oauth2/v3/userinfo" )
    val profilePipeline = authHeaders(accessToken) ~> sendReceive

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
  def getDownload(bucketName: String, objectKey: String, userAuthToken: WithAccessToken)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[PerRequestMessage] = {

    val objectStr = s"gs://$bucketName/$objectKey" // for logging
    // can we determine the current user's identity with Google?
    getUserProfile(userAuthToken) flatMap { userResponse =>
      userResponse.status match {
        case OK =>
          // user is known to Google. Extract the user's email and SID from the response, for logging
          import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impOAuthUser
          import spray.json._
          val oauthUser:Try[OAuthUser] = Try(userResponse.entity.asString.parseJson.convertTo[OAuthUser])
          val userStr = (oauthUser getOrElse userResponse.entity).toString
          // Does the user have access to the target file?
          objectAccessCheck(bucketName, objectKey, userAuthToken) flatMap { objectResponse =>
            objectResponse.status match {
              case OK =>
                val objMetadata: ObjectMetadata = xmlApiResponseToObject(objectResponse, bucketName, objectKey)

                // user has access to the object.
                // switch solutions based on the size of the target object. If the target object is small enough,
                // proxy it through orchestration; this allows embedded images inside HTML reports to render correctly.
                val objSize:Int = objMetadata.size.toInt
                // 8MB or under ...
                if (objSize > 0 && objSize < 8388608) {
                  logger.info(s"$userStr download via proxy allowed for [$objectStr]")
                  val gcsApiUrl = getXmlApiMetadataUrl(bucketName, objectKey)
                  val extReq = Get(gcsApiUrl)
                  val proxyPipeline = addCredentials(userAuthToken.accessToken) ~> sendReceive
                  // ensure we set the content-type correctly when proxying
                  proxyPipeline(extReq) map { proxyResponse:HttpResponse =>
                      proxyResponse.header[HttpHeaders.`Content-Type`] match {
                      case Some(ct) =>
                        RequestCompleteWithHeaders((proxyResponse.status, proxyResponse.entity),
                          HttpHeaders.`Content-Type`(ct.contentType)
                        )
                      case None => RequestComplete((proxyResponse.status, proxyResponse.entity))
                    }
                  }
                } else {
                  // object is too large to proxy; try to make a signed url.
                  // now make a final request to see if our service account has access, so it can sign a URL
                  objectAccessCheck(bucketName, objectKey, AccessToken(getRawlsServiceAccountAccessToken)) map { serviceAccountResponse =>
                    serviceAccountResponse.status match {
                      case OK =>
                        // the service account can read the object too. We are safe to sign a url.
                        logger.info(s"$userStr download via signed URL allowed for [$objectStr]")
                        val redirectUrl = getSignedUrl(bucketName, objectKey)
                        RequestCompleteWithHeaders(StatusCodes.TemporaryRedirect, HttpHeaders.Location(Uri(redirectUrl)))
                      case _ =>
                        // the service account cannot read the object, even though the user can. We cannot
                        // make a signed url, because the service account won't have permission to sign it.
                        // therefore, we rely on a direct link. We accept that a direct link is vulnerable to
                        // identity problems if the current user is signed in to multiple google identies in
                        // the same browser profile, but this is the best we can do.
                        // generate direct link per https://cloud.google.com/storage/docs/authentication#cookieauth
                        logger.info(s"$userStr download via direct link allowed for [$objectStr]")
                        val redirectUrl = getDirectDownloadUrl(bucketName, objectKey)
                        RequestCompleteWithHeaders(StatusCodes.TemporaryRedirect, HttpHeaders.Location(Uri(redirectUrl)))

                    }
                  }
                }

              case _ =>
                // the user does not have access to the object.
                val responseStr = objectResponse.entity.asString.replaceAll("\n","")
                logger.warn(s"$userStr download denied for [$objectStr], because (${objectResponse.status}): $responseStr")
                Future(RequestComplete(objectResponse))
            }
          }
        case _ =>
          // Google did not return a profile for this user; abort. Reloading will resolve the issue if it's caused by an expired token.
          logger.warn(s"Unknown user attempted download for [$objectStr] and was denied. User info (${userResponse.status}): ${userResponse.entity.asString}")
          Future(RequestComplete((Unauthorized, "There was a problem authorizing your download. Please reload FireCloud and try again.")))
      }
    }
  }

  /** Fetch the latest price list from Google. Returns only the subset of prices that we find we have use for. */
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    val pipeline: HttpRequest => Future[GooglePriceList] = sendReceive ~> decode(Gzip) ~> unmarshal[GooglePriceList]
    pipeline(Get(FireCloudConfig.GoogleCloud.priceListUrl))
  }

  /**
    * Updates an existing google drive spreadsheet with the provided data.
    *
    * @param withAccessToken       WithAccessToken
    * @param spreadsheetId  Spreadsheet ID
    * @param newContent     ValueRange
    * @return               JsObject representing the Google Update response
    */
  def updateSpreadsheet(withAccessToken: WithAccessToken, spreadsheetId: String, newContent: ValueRange): JsObject = {
    val credential = new GoogleCredential().setAccessToken(withAccessToken.accessToken.token)
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
    val existingRecords =
      // getValues may come through as an instantiated list of type null with zero entries due to Scala <> Java stuff
      if (Try(existingContent.getValues.size()).toOption.getOrElse(0) > 0)
        existingContent.getValues.tail.toList
      else
        List()

    val header: java.util.List[AnyRef] = newContent.getValues.head
    val newRecords: List[java.util.List[AnyRef]] = newContent.getValues.drop(1).toList

    // Go through existing records and update them in place
    val existingRecordsUpdated = existingRecords.map { existingRecord =>
      val matchingNewRecord = newRecords.find { newRecordCandidate =>
        newRecordCandidate.head == existingRecord.head
      }

      matchingNewRecord match {
        case Some(newRecord) => newRecord // Overwrite the entire row in place, adequate unless we want to preserve user-added columns
        case _ => existingRecord // Don't delete existing records that aren't in the new export
      }
    }

    // Create new records for newly-added projects
    val recordsToAppend = newRecords.filter { newRecordCandidate =>
      !existingRecords.exists { existingRecordCandidate =>
        existingRecordCandidate.head == newRecordCandidate.head
      }
    }

    List(header) ++ existingRecordsUpdated ++ recordsToAppend
  }

  override def trialBillingManagerRemoveBillingAccount(project: String, targetUserEmail: String): Boolean = {
    val projectName = s"projects/$project" // format needed by Google

    // as the service account, get the current billing info to make sure we are removing the right thing.
    // this call will fail if the free-tier billing account has already been removed from the project.
    val billingService = getCloudBillingManager(getTrialBillingManagerCredential())

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
          val noBillingRequest = getCloudBillingManager(getTrialBillingManagerCredential())
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
