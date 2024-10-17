package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.HttpResponse
import better.files.File
import com.google.api.services.storage.model.Bucket
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.rawls.model.{ErrorReportSource, GoogleProjectId}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}

import java.io.InputStream
import scala.concurrent.{ExecutionContext, Future}

object GoogleServicesDAO {
  lazy val serviceName = Subsystems.GoogleBuckets
}

trait GoogleServicesDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(GoogleServicesDAO.serviceName.value)

  def getAdminUserAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(accessToken: WithAccessToken)
                    (implicit executionContext: ExecutionContext): Future[HttpResponse]

  val fetchPriceList: Future[GooglePriceList]
  
  def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, objectContents: Array[Byte]): GcsPath
  def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, tempFile: File): GcsPath

  def deleteGoogleGroup(groupEmail: String) : Unit
  def createGoogleGroup(groupName: String): Option[String]
  def addMemberToAnonymizedGoogleGroup(groupName: String, targetUserEmail: String): Option[String]

  def status: Future[SubsystemStatus]
  override def serviceName: Subsystem = GoogleServicesDAO.serviceName

  def publishMessages(fullyQualifiedTopic: String, messages: Seq[String]): Future[Unit]

  def getBucket(bucketName: String, petKey: String, userProject: Option[GoogleProjectId]): Option[Bucket]
}
