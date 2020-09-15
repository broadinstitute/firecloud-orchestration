package org.broadinstitute.dsde.firecloud.mock

import java.io.{ByteArrayInputStream, InputStream}
import java.util.concurrent.LinkedBlockingQueue

import akka.actor.ActorRefFactory
import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, ProfileWrapper, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.{HttpResponse, StatusCodes}
import spray.json.JsObject
import spray.json._
import spray.routing.RequestContext

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
  override def getObjectMetadata(bucketName: String, objectKey: String, authToken: String)
                                (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata] = {
    Future.successful(ObjectMetadata("foo", "bar", "baz", "bla", "blah", None, Some("blahh"), "blahh", "blahh", "blahh", Some("blahh"), "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
  }

  override def listObjectsAsRawlsSA(bucketName: String, prefix: String): List[String] = List("foo", "bar")
  override def getObjectContentsAsRawlsSA(bucketName: String, objectKey: String): String = "my object contents"

  override def getUserProfile(accessToken: WithAccessToken)
                             (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = Future.failed(new UnsupportedOperationException)
  override def getDownload(bucketName: String, objectKey: String, userAuthToken: WithAccessToken)
                          (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[PerRequestMessage] = {Future.successful(RequestComplete(StatusCodes.NotImplemented))}
  override def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    Future.successful(GooglePriceList(GooglePrices(UsPriceItem(BigDecimal(0.01)), UsTieredPriceItem(Map(1024L -> BigDecimal(0.12)))), "v0", "18-November-2016"))
  }

  override def deleteGoogleGroup(groupEmail: String): Unit = Unit
  override def createGoogleGroup(groupName: String): Option[String] = Option("new-google-group@support.something.firecloud.org")
  override def addMemberToAnonymizedGoogleGroup(groupName: String, targetUserEmail: String): Option[String] = Option("user-email@something.com")

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, messages = None))

  override def publishMessages(fullyQualifiedTopic: String, messages: Seq[String]): Future[Unit] = {
    import scala.collection.JavaConversions._
    pubsubMessages.addAll(messages)
    Future.successful(())
  }
}
