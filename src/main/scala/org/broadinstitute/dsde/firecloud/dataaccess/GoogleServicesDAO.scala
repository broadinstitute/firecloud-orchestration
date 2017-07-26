package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.InputStream

import akka.actor.ActorRefFactory
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, SubsystemStatus}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import spray.http.HttpResponse
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}

object GoogleServicesDAO {
  lazy val serviceName = "Google"
}

trait GoogleServicesDAO extends ReportsSubsystemStatus {

  implicit val errorReportSource = ErrorReportSource(GoogleServicesDAO.serviceName)

  def getAdminUserAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(requestContext: RequestContext)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse]
  def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit
  def getObjectMetadata(bucketName: String, objectKey: String, userAuthToken: String)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata]
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList]

  def status: Future[SubsystemStatus]
  override def serviceName: String = GoogleServicesDAO.serviceName

}
