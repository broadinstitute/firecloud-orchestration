package org.broadinstitute.dsde.firecloud.mock

import java.io.InputStream

import akka.actor.ActorRefFactory
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import org.broadinstitute.dsde.firecloud.dataaccess.{GooglePriceList, GooglePrices, GoogleServicesDAO, UsPriceItem}
import org.broadinstitute.dsde.firecloud.model.OAuthTokens
import spray.http.HttpResponse
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}

class MockGoogleServicesDAO extends GoogleServicesDAO {
  override def getGoogleRedirectURI(state: String, approvalPrompt: String = "auto", overrideScopes: Option[Seq[String]] = None): String = "http://cloud.google.com/"
  override def getTokens(actualState: String,  expectedState: String, authCode: String): OAuthTokens = OAuthTokens(new GoogleTokenResponse)
  override def whitelistRedirect(userUri:String): String = ""
  override def getAdminUserAccessToken: String = ""
  override def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream = null
  override def getObjectResourceUrl(bucketName: String, objectKey: String): String = ""
  override def getUserProfile(requestContext: RequestContext)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = Future.failed(new UnsupportedOperationException)
  override def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit = {}
  override def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    Future.successful(new GooglePriceList(new GooglePrices(new UsPriceItem(BigDecimal(0.01))), "v0", "18-November-2016"))
  }
}
