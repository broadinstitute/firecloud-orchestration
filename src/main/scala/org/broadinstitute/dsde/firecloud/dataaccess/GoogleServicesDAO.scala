package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.InputStream
import akka.http.scaladsl.model.HttpResponse
import com.google.api.services.storage.model.Bucket
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import scala.concurrent.{ExecutionContext, Future}

object GoogleServicesDAO {
  lazy val serviceName = "Google"
}

trait GoogleServicesDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(GoogleServicesDAO.serviceName)

  def getAdminUserAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(accessToken: WithAccessToken)
                    (implicit executionContext: ExecutionContext): Future[HttpResponse]
  def getDownload(bucketName: String, objectKey: String, userAuthToken: WithAccessToken)
                 (implicit executionContext: ExecutionContext): Future[PerRequestMessage]
  def getObjectMetadata(bucketName: String, objectKey: String, userAuthToken: String)
                    (implicit executionContext: ExecutionContext): Future[ObjectMetadata]

  def listObjectsAsRawlsSA(bucketName: String, prefix: String): List[String]
  def getObjectContentsAsRawlsSA(bucketName: String, objectKey: String): String

  val fetchPriceList: Future[GooglePriceList]
  
  def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, objectContents: Array[Byte]): GcsPath

  def deleteGoogleGroup(groupEmail: String) : Unit
  def createGoogleGroup(groupName: String): Option[String]
  def addMemberToAnonymizedGoogleGroup(groupName: String, targetUserEmail: String): Option[String]

  def status: Future[SubsystemStatus]
  override def serviceName: String = GoogleServicesDAO.serviceName

  def publishMessages(fullyQualifiedTopic: String, messages: Seq[String]): Future[Unit]

  def getBucket(bucketName: String, petKey: String): Option[Bucket]
}
