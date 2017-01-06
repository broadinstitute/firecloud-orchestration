package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.ObjectMetadata
import org.scalatest.{FlatSpec, Matchers}
import spray.http._
import spray.json._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class HttpGoogleServicesDAOSpec extends FlatSpec with Matchers {

  val testProject = "broad-dsde-dev"
  implicit val system = ActorSystem("HttpGoogleCloudStorageDAOSpec")
  import system.dispatcher
  val gcsDAO = HttpGoogleServicesDAO

  behavior of "HttpGoogleServicesDAO"

  it should "fetch the current price list" in {

    val priceList: GooglePriceList = Await.result(HttpGoogleServicesDAO.fetchPriceList, Duration.Inf)

    priceList.version should startWith ("v")
    priceList.updated should not be empty
    priceList.prices.cpBigstoreStorage.us should be > BigDecimal(0)
    priceList.prices.cpComputeengineInternetEgressNA.tiers.size should be > 0
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
    val objectMetadata = response.entity.asString.parseJson.convertTo[ObjectMetadata]

    objectMetadata.bucket should equal("test-bucket")
    objectMetadata.name should equal("test-composite-object")
    objectMetadata.md5Hash should equal(None)
  }
}
