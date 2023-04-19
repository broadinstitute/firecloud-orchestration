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
