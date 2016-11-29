package org.broadinstitute.dsde.firecloud.dataaccess

import java.io.InputStream

import akka.actor.ActorRefFactory
import org.broadinstitute.dsde.firecloud.model.OAuthTokens
import spray.http.{HttpRequest, HttpResponse}
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}

trait GoogleServicesDAO {
  def getGoogleRedirectURI(state: String, approvalPrompt: String = "auto", overrideScopes: Option[Seq[String]] = None): String
  def getTokens(actualState: String,  expectedState: String, authCode: String): OAuthTokens
  def whitelistRedirect(userUri:String): String
  def getAdminUserAccessToken: String
  def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream
  def getObjectResourceUrl(bucketName: String, objectKey: String): String
  def getUserProfile(requestContext: RequestContext)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse]
  def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit
  def getObjectStats(bucketName: String, objectKey: String, authToken: String)
                    (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): HttpRequest
  def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList]
}