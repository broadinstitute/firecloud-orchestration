package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.{File, InputStream}

import akka.actor.ActorRefFactory
import com.google.api.services.storage.model.StorageObject
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, UserInfo}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import spray.http.HttpResponse
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}

trait GoogleServicesDAO {

  implicit val errorReportSource = ErrorReportSource("Google")

  def getAdminUserAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def writeBucketObjectFromFile(userInfo: UserInfo, bucketName: String, contentType: String, fileName: String, file: File): StorageObject
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(requestContext: RequestContext)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse]
  def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit
  def getObjectMetadata(bucketName: String, objectKey: String, userAuthToken: String)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata]
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList]
}
