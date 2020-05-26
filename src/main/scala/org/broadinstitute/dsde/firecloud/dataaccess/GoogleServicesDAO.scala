package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.InputStream

import akka.actor.ActorRefFactory
import com.google.api.client.auth.oauth2.Credential
import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
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
  def getTrialBillingManagerEmail: String
  def getTrialSpreadsheetAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(accessToken: WithAccessToken)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse]
  def getDownload(bucketName: String, objectKey: String, userAuthToken: WithAccessToken)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[PerRequestMessage]
  def getObjectMetadata(bucketName: String, objectKey: String, userAuthToken: String)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata]

  def listObjectsAsRawlsSA(bucketName: String, prefix: String): List[String]
  def getObjectContentsAsRawlsSA(bucketName: String, objectKey: String): String

  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList]
  def updateSpreadsheet(spreadsheetId: String, content: ValueRange): JsObject

  def trialBillingManagerRemoveBillingAccount(projectName: String, targetUserEmail: String): Boolean

  def deleteGoogleGroup(groupEmail: String) : Unit
  def createGoogleGroup(groupName: String): Option[String]
  def addMemberToAnonymizedGoogleGroup(groupName: String, targetUserEmail: String): Option[String]

  def status: Future[SubsystemStatus]
  override def serviceName: String = GoogleServicesDAO.serviceName

  def publishMessages(fullyQualifiedTopic: String, messages: Seq[String]): Future[Unit]
}
