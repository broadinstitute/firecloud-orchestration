package org.broadinstitute.dsde.firecloud.mock

import java.io.{ByteArrayInputStream, InputStream}
import java.util.concurrent.LinkedBlockingQueue

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.broadinstitute.dsde.firecloud.FireCloudException
import com.google.api.services.storage.model.Bucket
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.{ProfileWrapper, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.JsObject
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class MockGoogleServicesDAO extends GoogleServicesDAO {

  private final val spreadsheetJson = """{
                                        |  "properties": {
                                        |    "autoRecalc": "ON_CHANGE",
                                        |    "defaultFormat": {
                                        |      "backgroundColor": {
                                        |        "blue": 1.0,
                                        |        "green": 1.0,
                                        |        "red": 1.0
                                        |      },
                                        |      "padding": {
                                        |        "bottom": 2,
                                        |        "left": 3,
                                        |        "right": 3,
                                        |        "top": 2
                                        |      },
                                        |      "textFormat": {
                                        |        "bold": false,
                                        |        "fontFamily": "arial,sans,sans-serif",
                                        |        "fontSize": 10,
                                        |        "foregroundColor": {},
                                        |        "italic": false,
                                        |        "strikethrough": false,
                                        |        "underline": false
                                        |      },
                                        |      "verticalAlignment": "BOTTOM",
                                        |      "wrapStrategy": "OVERFLOW_CELL"
                                        |    },
                                        |    "locale": "en_US",
                                        |    "timeZone": "Etc/GMT",
                                        |    "title": "Billing User Report"
                                        |  },
                                        |  "sheets": [
                                        |    {
                                        |      "properties": {
                                        |        "gridProperties": {
                                        |          "columnCount": 26,
                                        |          "rowCount": 1000
                                        |        },
                                        |        "index": 0,
                                        |        "sheetId": 0,
                                        |        "sheetType": "GRID",
                                        |        "title": "Sheet1"
                                        |      }
                                        |    }
                                        |  ],
                                        |  "spreadsheetId": "randomId",
                                        |  "spreadsheetUrl": "https://docs.google.com/spreadsheets/d/randomId/edit"
                                        |}
                                        |""".stripMargin.parseJson.asJsObject
  final val spreadsheetUpdateJson = """{"spreadsheetId":"randomId","updatedRange":"Sheet1!A1:F45","updatedRows":45,"updatedCells":270,"updatedColumns":6}""".parseJson.asJsObject

  val pubsubMessages = new LinkedBlockingQueue[String]()

  override def getAdminUserAccessToken: String = "adminUserAccessToken"
  override def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream = {
    objectKey match {
      case "target-whitelist.txt" => new ByteArrayInputStream("firecloud-dev\ntarget-user".getBytes("UTF-8"))
      case "tcga-whitelist.txt" => new ByteArrayInputStream("firecloud-dev\ntcga-user".getBytes("UTF-8"))
      case _ => new ByteArrayInputStream(" ".getBytes("UTF-8"))
    }
  }
  override def getObjectResourceUrl(bucketName: String, objectKey: String): String = ""

  override def listObjectsAsRawlsSA(bucketName: String, prefix: String): List[String] = List("foo", "bar")
  override def getObjectContentsAsRawlsSA(bucketName: String, objectKey: String): String = "my object contents"

  override def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, objectContents: Array[Byte]): GcsPath = GcsPath(bucketName, objectKey)

  override def getUserProfile(accessToken: WithAccessToken)
                             (implicit executionContext: ExecutionContext): Future[HttpResponse] = Future.failed(new UnsupportedOperationException)
  override val fetchPriceList: Future[GooglePriceList] = {
    Future.successful(GooglePriceList(GooglePrices(Map("us" -> 0.01, "europe-west1" -> 0.02), UsTieredPriceItem(Map(1024L -> BigDecimal(0.12)))), "v0", "18-November-2016"))
  }

  override def deleteGoogleGroup(groupEmail: String): Unit = ()
  override def createGoogleGroup(groupName: String): Option[String] = Option("new-google-group@support.something.firecloud.org")
  override def addMemberToAnonymizedGoogleGroup(groupName: String, targetUserEmail: String): Option[String] = Option("user-email@something.com")
  override def getBucket(bucketName: String, petKey: String): Option[Bucket] = {
    bucketName match {
      case "usBucket" => Option(new Bucket().setName("usBucket").setLocation("US"))
      case "europeWest1Bucket"=> Option(new Bucket().setName("europeWest1").setLocation("EUROPE-WEST1"))
    }
  }

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, messages = None))

  override def publishMessages(fullyQualifiedTopic: String, messages: Seq[String]): Future[Unit] = {
    import scala.jdk.CollectionConverters._
    pubsubMessages.addAll(messages.asJava)
    Future.successful(())
  }
}
