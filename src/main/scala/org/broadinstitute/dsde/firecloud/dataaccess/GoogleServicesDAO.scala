package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.HttpResponse
import com.google.api.services.storage.model.Bucket
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus

import java.io.InputStream
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
