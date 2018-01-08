package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import com.google.api.services.sheets.v4.model.ValueRange
import org.broadinstitute.dsde.firecloud.model.ObjectMetadata
import org.scalatest.{FlatSpec, Matchers, PrivateMethodTester}
import spray.http._
import spray.json._

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class HttpGoogleServicesDAOSpec extends FlatSpec with Matchers with PrivateMethodTester {

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

  {
    val headers = List[AnyRef]("header 1", "header 2", "header 3").asJava

    val row1 = List[AnyRef]("proj 1", "free", "trial").asJava
    val row2 = List[AnyRef]("proj 2", "firecloud", "credits").asJava
    val row3 = List[AnyRef]("proj 3", "test", "data").asJava

    val cells1 = List(headers, row1, row2, row3)

    it should "have no changes if the data didn't change" in {
      check(
        newContent = cells1,
        existingContent = cells1,
        expectedOutput = cells1
      )
    }

    it should "not explode if there is no data (only headers)" in {
      check(
        newContent = List(headers),
        existingContent = List(headers),
        expectedOutput = List(headers)
      )
    }

    it should "not explode or delete data if the update has no data" in {
      check(
        newContent = List(headers),
        existingContent = cells1,
        expectedOutput = cells1
      )
    }

    it should "add a new row even if existing is empty" in {
      check(
        newContent = cells1,
        existingContent = List(headers),
        expectedOutput = cells1
      )
    }

    it should "add a new row without modifying existing ones" in {
      check(
        newContent = List(headers, row1, row2, row3),
        existingContent = List(headers, row1, row2),
        expectedOutput = List(headers, row1, row2, row3)
      )
    }

    it should "output the union of the rows (overlapping sets)" in {
      check(
        newContent = List(headers, row2, row3),
        existingContent = List(headers, row1, row2),
        expectedOutput = List(headers, row1, row2, row3)
      )
    }

    it should "output the union of the rows (disjoint sets)" in {
      check(
        newContent = List(headers, row3),
        existingContent = List(headers, row1, row2),
        expectedOutput = List(headers, row1, row2, row3)
      )
    }

    it should "locate a row in existing that moved based on its first column, and replace its remaining columns with new" in {

      val newRow1 = List[AnyRef](row1.get(0), "new data first col!", "new data second col!").asJava

      check(
        newContent = List(headers, row2, row3, newRow1),
        existingContent = List(headers, row1, row2, row3),
        expectedOutput = List(headers, newRow1, row2, row3) // Row 1 is still first, but has the new data
      )
    }

    it should "preserve order of rows if all the updates are out of order" in {
      check(
        newContent = List(headers, row2, row3, row1),
        existingContent = List(headers, row1, row2, row3),
        expectedOutput = List(headers, row1, row2, row3)
      )
    }

    def check(newContent: List[java.util.List[AnyRef]], existingContent: List[java.util.List[AnyRef]], expectedOutput: List[java.util.List[AnyRef]]): Unit = {
      // https://stackoverflow.com/a/24375762/818054
      val updatePreservingOrder = PrivateMethod('updatePreservingOrder)

      assert(expectedOutput == gcsDAO.invokePrivate(
        updatePreservingOrder(
          (new ValueRange).setValues(newContent.asJava),
          (new ValueRange).setValues(existingContent.asJava)
        )
      ).asInstanceOf[List[java.util.List[AnyRef]]])
    }
  }

}
