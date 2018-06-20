package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.UUID

import akka.actor.ActorSystem
import com.google.api.services.sheets.v4.model.ValueRange
import org.broadinstitute.dsde.firecloud.model.ObjectMetadata
import org.scalatest.{FlatSpec, Matchers, PrivateMethodTester}
import spray.http.HttpHeaders.RawHeader
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

  {
    val headers = List[AnyRef]("header 1", "header 2", "header 3").asJava

    val row1 = List[AnyRef]("proj 1", "free", "trial").asJava
    val row2 = List[AnyRef]("proj 2", "firecloud", "").asJava
    val row3 = List[AnyRef]("proj 3", null, "data").asJava

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

    // This happens when calling updateBillingReport on an empty/new spreadsheet
    it should "not explode if existing content is empty (no headers)" in {
      check(
        newContent = List(headers, row1, row2, row3),
        existingContent = null,
        expectedOutput = List(headers, row1, row2, row3)
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

    it should "output the union of the rows (overlapping sets, ordered)" in {
      check(
        newContent = List(headers, row2, row3),
        existingContent = List(headers, row1, row2),
        expectedOutput = List(headers, row1, row2, row3)
      )
    }

    it should "output the union of the rows (overlapping sets, out of order)" in {
      check(
        newContent = List(headers, row3, row2),
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

    {
      val newRow1 = List[AnyRef](row1.get(0), "r1 first col!", "r1 second col!").asJava
      val newRow3 = List[AnyRef](row3.get(0), "r3 first col!", "r3 second col!").asJava

      it should "update data in a row" in {
        check(
          newContent = List(headers, newRow1, row2, newRow3),
          existingContent = List(headers, row1, row2, row3),
          expectedOutput = List(headers, newRow1, row2, newRow3)
        )
      }

      it should "locate a row in existing that moved based on its first column, and replace its remaining columns with new" in {
        check(
          newContent = List(headers, row2, newRow3, newRow1),
          existingContent = List(headers, row1, row2, row3),
          expectedOutput = List(headers, newRow1, row2, newRow3) // Row 1, 3 retain positions but have new data
        )
      }
    }

    it should "preserve order of rows if all the updates are out of order" in {
      check(
        newContent = List(headers, row2, row3, row1),
        existingContent = List(headers, row1, row2, row3),
        expectedOutput = List(headers, row1, row2, row3)
      )
    }

    it should "use the header from the new data in the update" in {

      val newHeaders = List[AnyRef]("header 1", "header 2", "header 3", "header 4").asJava

      check(
        newContent = List(newHeaders, row1, row2, row3),
        existingContent = List(headers, row1, row2, row3),
        expectedOutput = List(newHeaders, row1, row2, row3)
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
