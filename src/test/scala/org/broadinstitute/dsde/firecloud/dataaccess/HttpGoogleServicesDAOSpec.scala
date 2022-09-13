package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.UUID
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.effect.{IO, Resource}
import com.google.cloud.storage.{BlobInfo, Storage, StorageException}
import com.google.cloud.storage.Storage.BlobWriteOption
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ObjectMetadata
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.workbench.google2.{GoogleStorageInterpreter, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.PrivateMethodTester
import org.scalatestplus.mockito.MockitoSugar.mock
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.charset.StandardCharsets
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.Duration
import cats.effect.Temporal
import cats.effect.std.Semaphore

class HttpGoogleServicesDAOSpec extends AnyFlatSpec with Matchers with PrivateMethodTester {

  val testProject = "broad-dsde-dev"
  val priceListUrl = ConfigFactory.load().getString("googlecloud.priceListUrl")
  val defaultPriceList = GooglePriceList(GooglePrices(Map("us" -> BigDecimal(-0.11)), UsTieredPriceItem(Map(1L -> BigDecimal(-0.22)))), "v1", "1")
  implicit val system = ActorSystem("HttpGoogleCloudStorageDAOSpec")
  import system.dispatcher
  val gcsDAO = new HttpGoogleServicesDAO(priceListUrl, defaultPriceList)

  behavior of "HttpGoogleServicesDAO"

  it should "default to the cached price list if it cannot fetch/parse one from Google" in {
    val errorGcsDAO = new HttpGoogleServicesDAO(priceListUrl + ".error", defaultPriceList)

    val priceList: GooglePriceList = Await.result(errorGcsDAO.fetchPriceList, Duration.Inf)

    priceList.version should startWith ("v")
    priceList.updated should not be empty
    priceList.prices.cpBigstoreStorage("us") shouldBe BigDecimal(-0.11)
    priceList.prices.cpComputeengineInternetEgressNA.tiers.size shouldBe 1
  }

  /** This test will fail if md5Hash is not optional. However, its relationship to the code that depends on this
    * behavior, [[HttpGoogleServicesDAO.getObjectMetadata()]], is not apparent. A "better" test might be an integration
    * test that actually calls [[HttpGoogleServicesDAO.getObjectMetadata()]].
    */
  it should "successfully parse JSON into ObjectMetadata when md5Hash is missing" in {

    // JSON obtained (and modified) from https://developers.google.com/apis-explorer/#p/storage/v1/storage.objects.get
    val json = """{
                  "kind": "storage#object",
                  "id": "test-bucket/test-composite-object/1122334455667000",
                  "selfLink": "https://www.googleapis.com/storage/v1/b/test-bucket/o/test-composite-object",
                  "name": "test-composite-object",
                  "bucket": "test-bucket",
                  "generation": "1122334455667000",
                  "metageneration": "1",
                  "contentType": "application/octet-stream",
                  "timeCreated": "2017-01-05T09:24:03.729Z",
                  "updated": "2017-01-05T09:24:03.729Z",
                  "storageClass": "STANDARD",
                  "timeStorageClassUpdated": "2017-01-05T09:24:03.729Z",
                  "size": "4",
                  "mediaLink": "https://www.googleapis.com/download/storage/v1/b/test-bucket/o/test-composite-object?generation=1122334455667000&alt=media",
                  "crc32c": "A1B2C3==",
                  "componentCount": 2,
                  "etag": "a1b2c3d4e5f6g7h="
                 }"""
    val response = HttpResponse(status = StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, json))

    import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impGoogleObjectMetadata

    val objectMetadata = Await.result(Unmarshal(response).to[ObjectMetadata], Duration.Inf)

    objectMetadata.bucket should equal("test-bucket")
    objectMetadata.name should equal("test-composite-object")
    objectMetadata.md5Hash should equal(None)
  }

  it should "successfully parse an HttpResponse from Google's XML API into ObjectMetadata" in {

    // set up test input
    val headerDefs: List[(String, String)] = List(
          ("x-guploader-uploadid", "AEnB2Uqy63hMqyo9SalDsk9foDeByWdg4EbjSa0uDD4c3DeadRVyGr5G39gwdSo9GBs-kXK6ezQJxPVs_SX4gnMJT5irWzGPQg"),
          ("expires", "Sun, 10 Jun 2018 14:54:10 GMT"),
          ("date", "Sun, 10 Jun 2018 14:54:10 GMT"),
          ("cache-control", "private, max-age=0"),
          ("last-modified", "Thu, 07 Jun 2018 17:19:41 GMT"),
          ("etag", "\"a2e5503a8383e44054a80a98fd9e7d7e\""),
          ("x-goog-generation", "1528391981989468"),
          ("x-goog-metageneration", "1"),
          ("x-goog-stored-content-encoding", "identity"),
          ("x-goog-stored-content-length", "22769"),
          ("content-type", "image/png"),
          ("x-goog-hash", "crc32c=rX1FBg=="),
          ("x-goog-hash", "md5=ouVQOoOD5EBUqAqY/Z59fg=="),
          ("x-goog-storage-class", "STANDARD"),
          ("accept-ranges", "bytes"),
          ("content-length", "22769"),
          ("server", "UploadServer"),
          ("alt-svc", "quic=\":443\"; ma=2592000; v=\"43,42,41,39,35\""),
          ("content-disposition", "dispo"),
          ("content-encoding", "enco")
    )

    val headers: List[HttpHeader] = headerDefs map {
      case (key:String, value:String) => RawHeader(key, value)
    }

    val testBucketName = s"my-bucket-${UUID.randomUUID().toString}"
    val testObjectName = s"my-folder-${UUID.randomUUID().toString}/my-object-${UUID.randomUUID().toString}"

    val response = HttpResponse(status = StatusCodes.OK).withHeaders(headers)

    // set up expected result
    val expected = ObjectMetadata(
      bucket = testBucketName,
      crc32c = "rX1FBg==",
      etag = "a2e5503a8383e44054a80a98fd9e7d7e",
      generation = "1528391981989468",
      id = s"$testBucketName/$testObjectName/1528391981989468",
      md5Hash = Some("ouVQOoOD5EBUqAqY/Z59fg=="),
      mediaLink = None,
      name = testObjectName,
      size = "22769",
      storageClass = "STANDARD",
      timeCreated = None,
      updated = "Thu, 07 Jun 2018 17:19:41 GMT",
      contentDisposition = Some("dispo"),
      contentEncoding = Some("enco"),
      contentType = Some("image/png"),
      estimatedCostUSD = None
    )

    // the test
    val objectMetadata = gcsDAO.xmlApiResponseToObject(response, testBucketName, testObjectName)
    assertResult(expected) { objectMetadata }

  }

  it should "return GcsPath for a successful object upload" in {
    // create local storage service
    val db = LocalStorageHelper.getOptions().getService()
    val localStorage = storageResource(db)

    val bucketName = GcsBucketName("some-bucket")
    val objectName = GcsObjectName("folder/object.json")

    val objectContents = "Hello world".getBytes(StandardCharsets.UTF_8)

    assertResult(GcsPath(bucketName, objectName)) {
      gcsDAO.streamUploadObject(localStorage, bucketName, objectName, objectContents)
    }
  }

  it should "throw error for an unsuccessful object upload" in {
    // under the covers, Storage.writer is the Google library method that gets called. So, mock that
    // and force it to throw
    val mockedException = new StorageException(418, "intentional unit test failure")
    val throwingStorageHelper = mock[Storage]
    when(throwingStorageHelper.writer(any[BlobInfo], any[BlobWriteOption]))
      .thenThrow(mockedException)
    val localStorage = storageResource(throwingStorageHelper)

    val bucketName = GcsBucketName("some-bucket")
    val objectName = GcsObjectName("folder/object.json")

    val objectContents = "Hello world".getBytes(StandardCharsets.UTF_8)

    val caught = intercept[StorageException] {
      gcsDAO.streamUploadObject(localStorage, bucketName, objectName, objectContents)
    }

    assertResult(mockedException) {
      caught
    }
  }

  private def storageResource(backingStore: Storage): Resource[IO, GoogleStorageService[IO]] = {
    // create local storage service
    implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
    import cats.effect.unsafe.implicits.global

    val semaphore = Semaphore[IO](1).unsafeRunSync()
    Resource.pure[IO, GoogleStorageService[IO]](GoogleStorageInterpreter[IO](backingStore, Some(semaphore)))
  }

}
