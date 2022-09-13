package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.dataaccess.MockSamDAO
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{AccessToken, ObjectMetadata, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, StorageService}
import org.broadinstitute.dsde.rawls.model.ErrorReport

import scala.concurrent.{ExecutionContext, Future}


/*  We don't do much testing of the HealthMonitor itself, because that's tested as part of
    workbench-libs. Here, we test routing, de/serialization, and the config we send into
    the HealthMonitor.
 */
class StorageApiServiceSpec extends BaseServiceSpec with StorageApiService with SprayJsonSupport {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override val storageServiceConstructor: UserInfo => StorageService = StorageService.constructor(app.copy(samDAO = new SamMockWithUserToken, googleServicesDAO = new GoogleMock))


  "should return positive cost if size is positive" in {
    Get("/api/storage/foo/bar") ~> dummyUserIdHeaders("user") ~> sealRoute(storageRoutes) ~> check {
      status should equal(OK)
      val response = responseAs[ObjectMetadata]
      val cost = response.estimatedCostUSD.get
      withClue("Cost should be greater than zero since mocked size is greater than 0") {
        assert(cost > 0)
      }
      withClue("Bucket should be returned in response") {
        assert(response.bucket == "foo")
      }
    }
  }

  "should still return 200 status even if the size is not a number" in {
    Get("/api/storage/foo2/bar") ~> dummyUserIdHeaders("user") ~> sealRoute(storageRoutes) ~> check {
      status should equal(OK)
      val response = responseAs[ObjectMetadata]
      assertResult(None) {
        response.estimatedCostUSD
      }
      withClue("Bucket should be returned in response") {
        assert(response.bucket == "foo2")
      }
    }
  }


  class SamMockWithUserToken extends MockSamDAO {
    override def getPetServiceAccountTokenForUser(user: WithAccessToken, scopes: Seq[String]): Future[AccessToken] = {
      Future.successful(new AccessToken(OAuth2BearerToken("foo")))
    }
  }

  class GoogleMock extends MockGoogleServicesDAO {
    override def getObjectMetadata(bucketName: String, objectKey: String, authToken: String)(implicit executionContext: ExecutionContext): Future[ObjectMetadata] = {
      if (bucketName == "foo")
        Future.successful(ObjectMetadata("foo", objectKey, "baz", "bla", "blah", None, Some("blahh"), "blahh", "10000000000000", "blahh", Some("blahh"), "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
      else if (bucketName == "exception")
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "No access"))
      else
        Future.successful(ObjectMetadata(bucketName, objectKey, "baz", "bla", "blah", None, Some("blahh"), "blahh", "", "blahh", Some("blahh"), "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
    }
  }
}

