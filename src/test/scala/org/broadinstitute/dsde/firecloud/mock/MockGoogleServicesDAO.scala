package org.broadinstitute.dsde.firecloud.mock

import java.io.InputStream

import akka.actor.ActorRefFactory
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, WithAccessToken, OAuthTokens}
import spray.http.{HttpRequest, HttpResponse}
import spray.json.{JsNumber, JsObject}
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}

class MockGoogleServicesDAO extends GoogleServicesDAO {
  override def getAdminUserAccessToken: String = ""
  override def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream = null
  override def getObjectResourceUrl(bucketName: String, objectKey: String): String = ""
  override def getObjectMetadata(bucketName: String, objectKey: String, authToken: String)
                                (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata] = {
    Future.successful(ObjectMetadata("foo", "bar", "baz", "bla", "blah", "blahh", "blahh", "blahh", "blahh", "blahh", "blahh", "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
  }
  override def getUserProfile(requestContext: RequestContext)
                             (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = Future.failed(new UnsupportedOperationException)
  override def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                          (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit = {}
  override def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    Future.successful(new GooglePriceList(new GooglePrices(new UsPriceItem(BigDecimal(0.01)), UsTieredPriceItem(Map(1024L -> BigDecimal(0.12)))), "v0", "18-November-2016"))
  }
}
