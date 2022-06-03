package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.firecloud.dataaccess.{MockSamDAO, UsTieredPriceItem}
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.{AccessToken, ObjectMetadata, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.webservice.StorageApiService

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Created by mbemis on 12/2/16.
 */
class StorageServiceSpec extends BaseServiceSpec with StorageServiceSupport with StorageApiService {

  val sampleEgressCharges = UsTieredPriceItem(Map(1024L -> BigDecimal(0.12), 10240L -> BigDecimal(0.11), 92160L -> BigDecimal(0.08))).tiers.toList
  val sampleMissingEgressCharges = UsTieredPriceItem(Map.empty).tiers.toList
  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override val storageServiceConstructor: UserInfo => StorageService = StorageService.constructor(app.copy(samDAO = new SamMockWithUserToken, googleServicesDAO = new GoogleMock))

  val userToken: UserInfo = UserInfo("me@me.com", OAuth2BearerToken(""), 3600, "111")

  lazy val storageService: StorageService = storageServiceConstructor(userToken)

  "StorageService" - {
    "should calculate egress costs for single-tier objects" in {
      assertResult(Some(BigDecimal(122.88))) {
        getEgressCost(sampleEgressCharges, BigDecimal(1024), 0)
      }
    }

    "should calculate egress costs for mutli-tier objects" in {
      assertResult(Some(BigDecimal(235.52))) {
        getEgressCost(sampleEgressCharges, BigDecimal(2048), 0)
      }
    }

    "should return None when price list tiers don't exist" in {
      assertResult(None) {
        getEgressCost(sampleMissingEgressCharges, BigDecimal(555), 0)
      }
    }

    "should convert object size to positive cost" in {
      val result = Await.result(storageService.getObjectStats("foo", "bar"), Duration.Inf)
      val objectMetadata = result.response._2
      val cost = objectMetadata.estimatedCostUSD.get
      withClue("Cost should greater than zero since mocked size is greater than 0") {
        assert(cost > 0)
      }
    }

    "should still return 200 status even if the size is not a number" in {
      val result = Await.result(storageService.getObjectStats("foo2", "bar"), Duration.Inf)
      val status = result.response._1
      assert(status == StatusCodes.OK)
      val objectMetadata = result.response._2
      assertResult(None){
        objectMetadata.estimatedCostUSD
      }
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
    if(bucketName == "foo")
      Future.successful(ObjectMetadata("foo", "bar", "baz", "bla", "blah", None, Some("blahh"), "blahh", "10000000000000", "blahh", Some("blahh"), "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
    else
      Future.successful(ObjectMetadata("foo", "bar", "baz", "bla", "blah", None, Some("blahh"), "blahh", "", "blahh", Some("blahh"), "blahh", Option("blahh"), Option("blahh"), Option("blahh"), None))
  }
}
