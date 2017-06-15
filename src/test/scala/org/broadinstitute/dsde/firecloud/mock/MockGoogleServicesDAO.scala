package org.broadinstitute.dsde.firecloud.mock

import java.io.{ByteArrayInputStream, File, InputStream}

import akka.actor.ActorRefFactory
import com.google.api.services.storage.model.StorageObject
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.{ObjectMetadata, UserInfo}
import spray.http.HttpResponse
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class MockGoogleServicesDAO extends GoogleServicesDAO {
  override def getAdminUserAccessToken: String = ""
  override def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream = {
    objectKey match {
      case "target-whitelist.txt" => new ByteArrayInputStream("firecloud-dev\ntarget-user".getBytes("UTF-8"))
      case "tcga-whitelist.txt" => new ByteArrayInputStream("firecloud-dev\ntcga-user".getBytes("UTF-8"))
      case _ => new ByteArrayInputStream(" ".getBytes("UTF-8"))
    }
  }
  override def writeFileToBucket(userInfo: UserInfo, bucketName: String, contentType: String, fileName: String, file: File): StorageObject = {
    new StorageObject().setName(fileName).setBucket(bucketName).setContentType(contentType)
  }

  override def getSignedUrl(bucketName: String, objectKey: String, expireSeconds: Long): String = {
    val clientId = new Random().nextString(10)
    val signedBytes = new Random().nextString(10).getBytes
    s"https://storage.googleapis.com/$bucketName/$objectKey" +
      s"?GoogleAccessId=$clientId" +
      s"&Expires=$expireSeconds" +
      "&Signature=" + java.net.URLEncoder.encode(java.util.Base64.getEncoder.encodeToString(signedBytes), "UTF-8")
  }

  override def getObjectResourceUrl(bucketName: String, objectKey: String): String = ""
  override def getObjectMetadata(bucketName: String, objectKey: String, authToken: String)
                                (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[ObjectMetadata] = {
    Future.successful(ObjectMetadata("foo", "bar", "baz", "bla", "blah", None, "blahh", "blahh", "blahh", "blahh", "blahh", "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
  }
  override def getUserProfile(requestContext: RequestContext)
                             (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[HttpResponse] = Future.failed(new UnsupportedOperationException)
  override def getDownload(requestContext: RequestContext, bucketName: String, objectKey: String, userAuthToken: String)
                          (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Unit = {}
  override def fetchPriceList(implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): Future[GooglePriceList] = {
    Future.successful(new GooglePriceList(new GooglePrices(new UsPriceItem(BigDecimal(0.01)), UsTieredPriceItem(Map(1024L -> BigDecimal(0.12)))), "v0", "18-November-2016"))
  }
}
