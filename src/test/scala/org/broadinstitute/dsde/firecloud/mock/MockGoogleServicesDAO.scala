package org.broadinstitute.dsde.firecloud.mock

import java.io.{ByteArrayInputStream, InputStream}

import akka.actor.ActorRefFactory
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.ObjectMetadata
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.http.HttpResponse
import spray.routing.RequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class MockGoogleServicesDAO extends GoogleServicesDAO {
  override def getAdminUserAccessToken: String = ""
  override def getTrialBillingManagerAccessToken: String = ""
  override def getBucketObjectAsInputStream(bucketName: String, objectKey: String): InputStream = {
    objectKey match {
      case "target-whitelist.txt" => new ByteArrayInputStream("firecloud-dev\ntarget-user".getBytes("UTF-8"))
      case "tcga-whitelist.txt" => new ByteArrayInputStream("firecloud-dev\ntcga-user".getBytes("UTF-8"))
      case _ => new ByteArrayInputStream(" ".getBytes("UTF-8"))
    }
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

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, messages = None))

}
