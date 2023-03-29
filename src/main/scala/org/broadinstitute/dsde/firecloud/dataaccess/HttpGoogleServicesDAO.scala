package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpResponse
import akka.stream.Materializer
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.directory.model.{Group, Member}
import com.google.api.services.directory.{Directory, DirectoryScopes}
import com.google.api.services.pubsub.model.{PublishRequest, PubsubMessage}
import com.google.api.services.pubsub.{Pubsub, PubsubScopes}
import com.google.api.services.storage.model.{Bucket, Objects}
import com.google.api.services.storage.{Storage, StorageScopes}
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.{GoogleCredentials, ServiceAccountCredentials}
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.HttpGoogleServicesDAO._
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.FireCloudRequestBuilding
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath, GoogleProject}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import spray.json.{DefaultJsonProtocol, _}

import java.io.{ByteArrayInputStream, FileInputStream}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Result from Google's pricing calculator price list
  * (https://cloudpricingcalculator.appspot.com/static/data/pricelist.json).
  */
case class GooglePriceList(prices: GooglePrices, version: String, updated: String)

/** Partial price list. Attributes can be added as needed to import prices for more products. */
case class GooglePrices(cpBigstoreStorage: Map[String, BigDecimal],
                        cpComputeengineInternetEgressNA: UsTieredPriceItem)

/** Tiered price item containing only US currency.
 *
 * Used for egress, may need to be altered to work with other types in the future.
 * Contains a map of the different tiers of pricing, where the key is the size in GB
 * for that tier and the value is the cost in USD for that tier.
 */
case class UsTieredPriceItem(tiers: Map[Long, BigDecimal])

object GooglePriceListJsonProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit object UsTieredPriceItemFormat extends RootJsonFormat[UsTieredPriceItem] {
    override def write(value: UsTieredPriceItem): JsValue = ???
    override def read(json: JsValue): UsTieredPriceItem = json match {
      case JsObject(values) => UsTieredPriceItem(values("tiers").asJsObject.fields.map{ case (name, value) => name.toLong -> BigDecimal(value.toString)})
      case x => throw new DeserializationException("invalid value: " + x)
    }
  }
  implicit val GooglePricesFormat = jsonFormat(GooglePrices, FireCloudConfig.GoogleCloud.priceListStorageKey, FireCloudConfig.GoogleCloud.priceListEgressKey)
  implicit val GooglePriceListFormat = jsonFormat(GooglePriceList, "gcp_price_list", "version", "updated")
}
import org.broadinstitute.dsde.firecloud.dataaccess.GooglePriceListJsonProtocol._

object HttpGoogleServicesDAO {
  // the minimal scopes needed to get through the auth proxy and populate our UserInfo model objects
  val authScopes = Seq("profile", "email")
  // the minimal scope to read from GCS
  val storageReadOnly = Seq(StorageScopes.DEVSTORAGE_READ_ONLY)
  // scope for creating anonymized Google groups
  val directoryScope = Seq(DirectoryScopes.ADMIN_DIRECTORY_GROUP)
  // the scope we want is not defined in CloudbillingScopes, so we hardcode it here
  val billingScope = Seq("https://www.googleapis.com/auth/cloud-billing")

  private def getScopedCredentials(baseCreds: GoogleCredentials, scopes: Seq[String]): GoogleCredentials = {
    baseCreds.createScoped(scopes.asJava)
  }

  private def getScopedServiceAccountCredentials(baseCreds: ServiceAccountCredentials, scopes: Seq[String]): ServiceAccountCredentials = {
    getScopedCredentials(baseCreds, scopes) match {
      case sa:ServiceAccountCredentials => sa
      case ex => throw new Exception(s"Excpected a ServiceAccountCredentials instance, got a ${ex.getClass.getName}")
    }
  }

  // credentials for orchestration's "firecloud" service account, used for admin duties
  lazy private val firecloudAdminSACreds = ServiceAccountCredentials
    .fromStream(new FileInputStream(FireCloudConfig.Auth.firecloudAdminSAJsonFile))

  def getAdminUserAccessToken = {
    getScopedServiceAccountCredentials(firecloudAdminSACreds, authScopes)
      .refreshAccessToken().getTokenValue
  }
}

class HttpGoogleServicesDAO(priceListUrl: String, defaultPriceList: GooglePriceList)(implicit val system: ActorSystem, implicit val materializer: Materializer, implicit val executionContext: ExecutionContext) extends GoogleServicesDAO with FireCloudRequestBuilding with LazyLogging with RestJsonClient with SprayJsonSupport {

  // application name to use within Google api libraries
  private final val appName = "firecloud:orchestration"

  val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  val jsonFactory = GsonFactory.getDefaultInstance

  // credentials for the rawls service account, used for signing GCS urls
  lazy private val rawlsSACreds = ServiceAccountCredentials
    .fromStream(new FileInputStream(FireCloudConfig.Auth.rawlsSAJsonFile))

  // credential for creating anonymized Google groups
  val userAdminAccount = FireCloudConfig.FireCloud.userAdminAccount
  // settings for users in anonymized Google groups
  val anonymizedGroupRole = "MEMBER"
  val anonymizedGroupDeliverySettings = "ALL_MAIL"

  private def getDelegatedCredentials(baseCreds: GoogleCredentials, user: String): GoogleCredentials= {
    baseCreds.createDelegated(user)
  }

  def getDirectoryManager(credential: GoogleCredentials): Directory = {
    new Directory.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(credential.createScoped(directoryScope.asJava))).setApplicationName(appName).build()
  }

  private lazy val pubSub = {
    new Pubsub.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(getPubSubServiceAccountCredential)).setApplicationName(appName).build()
  }

  private def getDelegatedCredentialForAdminUser: GoogleCredentials = {
    getDelegatedCredentials(firecloudAdminSACreds, userAdminAccount)
  }

  private def getBucketServiceAccountCredential = {
    getScopedServiceAccountCredentials(firecloudAdminSACreds, storageReadOnly)
  }

  private def getPubSubServiceAccountCredential = {
    getScopedServiceAccountCredentials(firecloudAdminSACreds, Seq(PubsubScopes.PUBSUB))
  }

  def getRawlsServiceAccountCredential = {
    getScopedServiceAccountCredentials(rawlsSACreds, storageReadOnly)
  }

  def getRawlsServiceAccountAccessToken = {
    getRawlsServiceAccountCredential.refreshAccessToken().getTokenValue
  }

  override def listObjectsAsRawlsSA(bucketName: String, prefix: String): List[String] = {
    val storage = new Storage.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(getRawlsServiceAccountCredential)).setApplicationName(appName).build()
    val listRequest = storage.objects().list(bucketName).setPrefix(prefix)
    Try(executeGoogleRequest[Objects](listRequest)) match {
      case Failure(ex) =>
        // handle this case so we can give a good log message. In the future we may handle this
        // differently, such as returning an empty list.
        logger.warn(s"could not list objects in bucket/prefix gs://$bucketName/$prefix", ex)
        throw ex
      case Success(obs) =>
        Option(obs.getItems) match {
          case None => List.empty[String]
          case Some(items) => items.asScala.toList.map { ob =>
            ob.getName
          }
        }
    }
  }

  // WARNING: only call on smallish objects!
  override def getObjectContentsAsRawlsSA(bucketName: String, objectKey: String): String = {
    val storage = new Storage.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(getRawlsServiceAccountCredential)).setApplicationName(appName).build()
    val is = storage.objects().get(bucketName, objectKey).executeMediaAsInputStream
    scala.io.Source.fromInputStream(is).mkString
  }

  /**
    * Uploads the supplied data to GCS, using the Rawls service account credentials
    * @param bucketName target bucket name for upload
    * @param objectKey target object name for upload
    * @param objectContents byte array of the data to upload
    * @return path to the uploaded GCS object
    */
  override def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, objectContents: Array[Byte]): GcsPath = {
    // if other methods in this class use google2/need these implicits, consider moving to the class level
    // instead of here at the method level
    implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

    // create the storage service, using the Rawls SA credentials
    // the Rawls SA json creds do not contain a project, so also specify the project explicitly
    val storageResource = GoogleStorageService.resource(FireCloudConfig.Auth.rawlsSAJsonFile, Option.empty[Semaphore[IO]],
       project = Some(GoogleProject(FireCloudConfig.FireCloud.serviceProject)))

    // call the upload implementation
    streamUploadObject(storageResource, bucketName, objectKey, objectContents)
  }

  // separate method to perform the upload, to ease unit testing
  protected[dataaccess] def streamUploadObject(storageResource: Resource[IO, GoogleStorageService[IO]], bucketName: GcsBucketName,
                                   objectKey: GcsObjectName, objectContents: Array[Byte]): GcsPath = {
    // create the stream of data to upload
    val dataStream: Stream[IO, Byte] = Stream.emits(objectContents).covary[IO]

    val uploadAttempt = storageResource.use { storageService =>
      // create the destination pipe to which we will write the file
      // N.B. workbench-libs' streamUploadBlob does not allow setting the Content-Type, so we don't set it
      val uploadPipe = storageService.streamUploadBlob(bucketName, GcsBlobName(objectKey.value))
      // stream the data to the destination pipe
      dataStream.through(uploadPipe).compile.drain
    }

    // execute the upload
    uploadAttempt.unsafeRunSync()

    // finally, return a GcsPath
    GcsPath(bucketName, objectKey)
  }

  def getBucketObjectAsInputStream(bucketName: String, objectKey: String) = {
    val storage = new Storage.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(getBucketServiceAccountCredential)).setApplicationName(appName).build()
    storage.objects().get(bucketName, objectKey).executeMediaAsInputStream
  }

  def getBucket(bucketName: String, petKey: String): Option[Bucket] = {
    val keyStream = new ByteArrayInputStream(petKey.getBytes)
    val credential = getScopedServiceAccountCredentials(ServiceAccountCredentials.fromStream(keyStream), storageReadOnly)

    val storage = new Storage.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(credential)).setApplicationName(appName).build()

    Try(executeGoogleRequest[Bucket](storage.buckets().get(bucketName))) match {
      case Failure(ex) =>
        // handle this case so we can give a good log message. In the future we may handle this
        // differently, such as returning an empty list.
        logger.warn(s"could not get $bucketName", ex)
        throw ex
      case Success(response) =>
        return Option(response)
    }
  }

  def getObjectResourceUrl(bucketName: String, objectKey: String) = {
    val gcsStatUrl = "https://www.googleapis.com/storage/v1/b/%s/o/%s"
    gcsStatUrl.format(bucketName, java.net.URLEncoder.encode(objectKey,"UTF-8"))
  }

  def getUserProfile(accessToken: WithAccessToken)
                    (implicit executionContext: ExecutionContext): Future[HttpResponse] = {
    val profileRequest = Get( "https://www.googleapis.com/oauth2/v3/userinfo" )

    userAuthedRequest(profileRequest)(accessToken)
  }

  /** Fetch the latest price list from Google. Returns only the subset of prices that we find we have use for. */
  //Why is this a val? Because the price lists do not change very often. This prevents making an HTTP call to Google
  //every time we want to calculate a cost estimate (which happens extremely often in the Terra UI)
  //Because the price list is brittle and Google sometimes changes the names of keys in the JSON, there is a
  //default cached value in configuration to use as a backup. If we fallback to it, the error will be logged
  //but users will probably not notice a difference. They're cost *estimates*, after all.
  lazy val fetchPriceList: Future[GooglePriceList] = {
    val httpReq = Get(priceListUrl)

    unAuthedRequestToObject[GooglePriceList](httpReq).recover {
      case t: Throwable =>
        logger.error(s"Unable to fetch/parse latest Google price list. A cached (possibly outdated) value will be used instead. Error: ${t.getMessage}")
        defaultPriceList
    }
  }

  override def deleteGoogleGroup(groupEmail: String): Unit = {
    val directoryService = getDirectoryManager(getDelegatedCredentialForAdminUser)
    val deleteGroupRequest = directoryService.groups.delete(groupEmail)
    Try(executeGoogleRequest(deleteGroupRequest)) match {
      case Failure(_) => logger.warn(s"Failed to delete group $groupEmail")
      case Success(_) =>
    }
  }

  /**
    * create a new Google group
    * @param groupEmail     the name of the new Google group to be created. it must be formatted as an email address
    * @return               Option of the groupEmail address if all above is successful, otherwise if the group creation
    *                        step failed, return Option.empty
    */
  override def createGoogleGroup(groupEmail: String): Option[String] = {
    val newGroup = new Group().setEmail(groupEmail)
    val directoryService = getDirectoryManager(getDelegatedCredentialForAdminUser)

    val insertRequest = directoryService.groups.insert(newGroup)
    Try(executeGoogleRequest[Group](insertRequest)) match {
      case Failure(response: GoogleJsonResponseException) => {
        val errorCode = response.getDetails.getCode
        val message = response.getDetails.getMessage
        logger.warn(s"Error $errorCode: Could not create new group $groupEmail; $message")
        Option.empty
      }
      case Failure(f) => {
        logger.warn(s"Error: Could not create new group $groupEmail: $f")
        Option.empty
      }
      case Success(newGroupInfo) => {
        Option(newGroupInfo.getEmail())
      }
    }
  }

  /**
    * add a new member (Terra user email address) to an existing Google group. this is currently set up with settings for
    * anonymized Google groups, which set the user to be a member of the group and allow them to receive emails via the group address.
    * @param groupEmail       is the name of the existing Google group. it must be formatted as an email address
    * @param targetUserEmail  is the email address of the Terra user to be added to the Google group
    * @return                 Option of the email address of the user if all above is successful, otherwise if the group
    *                         creation step failed, return Option.empty
    */
  override def addMemberToAnonymizedGoogleGroup(groupEmail: String, targetUserEmail: String): Option[String] = {
    val directoryService = getDirectoryManager(getDelegatedCredentialForAdminUser)

    // add targetUserEmail as member of google group - modeled after `override def addMemberToGroup` in workbench-libs HttpGoogleDirectoryDAO.scala
    val member = new Member().setEmail(targetUserEmail).setRole(anonymizedGroupRole).setDeliverySettings(anonymizedGroupDeliverySettings)
    val memberInsertRequest = directoryService.members.insert(groupEmail, member)

    Try(executeGoogleRequest(memberInsertRequest)) match {
      case Failure(response: GoogleJsonResponseException) => {
        val errorCode = response.getDetails.getCode
        val message = response.getDetails.getMessage
        logger.warn(s"Error $errorCode: Could not add new member $targetUserEmail to group $groupEmail; $message")
        deleteGoogleGroup(groupEmail) // try to clean up after yourself
        Option.empty
      }
      case Failure(f) => {
        logger.warn(s"Error: Could not add new member $targetUserEmail to group $groupEmail; $f")
        deleteGoogleGroup(groupEmail)
        Option.empty
      }
      case Success(_) => {
        Option(targetUserEmail) // return email address of added user (string)
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
    val storage = new Storage.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(getBucketServiceAccountCredential)).setApplicationName(appName).build()
    val bucketResponseTry = Try(storage.buckets().list(FireCloudConfig.FireCloud.serviceProject).executeUsingHead())
    bucketResponseTry match {
      case scala.util.Success(bucketResponse) => bucketResponse.getStatusCode match {
        case x if x == 200 => Future(SubsystemStatus(ok = true, messages = None))
        case _ => Future(SubsystemStatus(ok = false, messages = Some(List(bucketResponse.parseAsString()))))
      }
      case Failure(ex) => Future(SubsystemStatus(ok = false, messages = Some(List(ex.getMessage))))
    }
  }

  override def publishMessages(fullyQualifiedTopic: String, messages: Seq[String]): Future[Unit] = {
    logger.debug(s"publishing to google pubsub topic $fullyQualifiedTopic, messages [${messages.mkString(", ")}]")
    Future.traverse(messages.grouped(1000)) { messageBatch =>
      val pubsubMessages = messageBatch.map(text => new PubsubMessage().encodeData(text.getBytes("UTF-8")))
      val pubsubRequest = new PublishRequest().setMessages(pubsubMessages.asJava)
      Future(executeGoogleRequest(pubSub.projects().topics().publish(fullyQualifiedTopic, pubsubRequest)))
    }.map(_ => ())
  }

  override def getAdminUserAccessToken = {
    getScopedServiceAccountCredentials(firecloudAdminSACreds, authScopes)
      .refreshAccessToken().getTokenValue
  }
}
