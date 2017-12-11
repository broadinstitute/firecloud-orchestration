package org.broadinstitute.dsde.firecloud.mock

import java.io.{ByteArrayInputStream, InputStream}

import akka.actor.ActorRefFactory
import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, UserInfo}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.HttpResponse
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

  override def getAdminUserAccessToken: String = "adminUserAccessToken"
  override def getTrialBillingManagerAccessToken(impersonateUser: Option[String] = None): String = "billingManagerAccessToken"
  override def getTrialBillingManagerEmail: String = "mock-trial-billing-mgr-email"
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
    Future.successful(ObjectMetadata("foo", "bar", "baz", "bla", "blah", None, "blahh", "blahh", "blahh", "blahh", "blahh", "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
  }
  override def getUserProfile(requestContext: RequestContext)
                             (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = Future.failed(new UnsupportedOperationException)
  override def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                          (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit = {}
  override def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    Future.successful(GooglePriceList(GooglePrices(UsPriceItem(BigDecimal(0.01)), UsTieredPriceItem(Map(1024L -> BigDecimal(0.12)))), "v0", "18-November-2016"))
  }
  override def createSpreadsheet(requestContext: RequestContext, userInfo: UserInfo, props: SpreadsheetProperties): JsObject = spreadsheetJson
  override def updateSpreadsheet(requestContext: RequestContext, userInfo: UserInfo, spreadsheetId: String, content: ValueRange): JsObject = spreadsheetUpdateJson


  override def trialBillingManagerRemoveBillingAccount(projectName: String, targetUserEmail: String): Boolean = false

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, messages = None))

}
