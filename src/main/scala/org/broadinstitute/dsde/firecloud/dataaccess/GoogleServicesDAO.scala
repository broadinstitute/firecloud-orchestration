package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.InputStream

import akka.actor.ActorRefFactory
import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, UserInfo}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.HttpResponse
import spray.json.JsObject
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}

object GoogleServicesDAO {
  lazy val serviceName = "Google"
}

trait GoogleServicesDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(GoogleServicesDAO.serviceName)

  def getAdminUserAccessToken: String
  def getTrialBillingManagerAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(requestContext: RequestContext)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse]
  def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit
  def getObjectMetadata(bucketName: String, objectKey: String, userAuthToken: String)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata]
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList]
  def createSpreadsheet(requestContext: RequestContext, userInfo: UserInfo, props: SpreadsheetProperties): JsObject
  def updateSpreadsheet(requestContext: RequestContext, userInfo: UserInfo, spreadsheetId: String, content: ValueRange): JsObject

  def status: Future[SubsystemStatus]
  override def serviceName: String = GoogleServicesDAO.serviceName

}
