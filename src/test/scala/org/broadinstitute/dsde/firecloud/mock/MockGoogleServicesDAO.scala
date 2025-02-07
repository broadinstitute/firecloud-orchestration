package org.broadinstitute.dsde.firecloud.mock

import akka.http.scaladsl.model.HttpResponse
import better.files.File
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json._

import java.io.{ByteArrayInputStream, InputStream}
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class MockGoogleServicesDAO extends GoogleServicesDAO {

  final private val spreadsheetJson = """{
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
  final val spreadsheetUpdateJson =
    """{"spreadsheetId":"randomId","updatedRange":"Sheet1!A1:F45","updatedRows":45,"updatedCells":270,"updatedColumns":6}""".parseJson.asJsObject

  val pubsubMessages = new LinkedBlockingQueue[String]()

  override def getAdminUserAccessToken: String = "adminUserAccessToken"
  override def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream =
    objectKey match {
      case "target-whitelist.txt" => new ByteArrayInputStream("firecloud-dev\ntarget-user".getBytes("UTF-8"))
      case "tcga-whitelist.txt"   => new ByteArrayInputStream("firecloud-dev\ntcga-user".getBytes("UTF-8"))
      case _                      => new ByteArrayInputStream(" ".getBytes("UTF-8"))
    }
  override def getObjectResourceUrl(bucketName: String, objectKey: String): String = ""

  override def writeObjectAsRawlsSA(bucketName: GcsBucketName,
                                    objectKey: GcsObjectName,
                                    objectContents: Array[Byte]
  ): GcsPath = GcsPath(bucketName, objectKey)
  override def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, tempFile: File): GcsPath =
    GcsPath(bucketName, objectKey)

  override def deleteGoogleGroup(groupEmail: String): Unit = ()
  override def createGoogleGroup(groupName: String): Option[String] = Option(
    "new-google-group@support.something.firecloud.org"
  )
  override def addMemberToAnonymizedGoogleGroup(groupName: String, targetUserEmail: String): Option[String] = Option(
    "user-email@something.com"
  )

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, messages = None))

  override def publishMessages(fullyQualifiedTopic: String, messages: Seq[String]): Future[Unit] = {
    import scala.jdk.CollectionConverters._
    pubsubMessages.addAll(messages.asJava)
    Future.successful(())
  }

  override def listBucket(bucketName: GcsBucketName, prefix: Option[String], recursive: Boolean): List[GcsObjectName] =
    List()
}
