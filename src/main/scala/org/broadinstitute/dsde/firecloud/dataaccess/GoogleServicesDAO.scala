package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.InputStream

import akka.actor.ActorRefFactory
import org.broadinstitute.dsde.firecloud.model.OAuthTokens
import spray.http.{HttpRequest, HttpResponse}
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}

trait GoogleServicesDAO {
  def getAdminUserAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(requestContext: RequestContext)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse]
  def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit
  def getObjectMetadataRequest(bucketName: String, objectKey: String)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): HttpRequest
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList]
}
