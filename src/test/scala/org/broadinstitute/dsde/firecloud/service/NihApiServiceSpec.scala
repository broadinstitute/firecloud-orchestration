package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.JWTWrapper
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import spray.http.StatusCodes._

import scala.concurrent.ExecutionContext

class NihApiServiceSpec extends ApiServiceSpec {
  val dbGapAuthorizedUsersGroupName = FireCloudConfig.Nih.whitelists.filter(_.name.equals("dbGaP")).head.groupToSync
  val validJwtOnWhitelist = JWTWrapper("eyJhbGciOiJIUzI1NiJ9.ZmlyZWNsb3VkLWRldg.NPXbSpTmAOUvJ1HX85TauAARnlMKfqBsPjumCC7zE7s")
  val validJwtNotOnWhitelist = JWTWrapper("eyJhbGciOiJIUzI1NiJ9.bm90LWEtcmVhbC11c2Vy.L6JKlzZ9zp2mvDAbhGKRJIbW99x4cN-UjWJNZuSdnbc")

  case class TestApiService(agoraDao: MockAgoraDAO, googleDao: MockGoogleServicesDAO, ontologyDao: MockOntologyDAO, rawlsDao: MockRawlsDAO, searchDao: MockSearchDAO, thurloeDao: MockThurloeDAO)(implicit val executionContext: ExecutionContext) extends ApiServices

  def withDefaultApiServices[T](testCode: TestApiService => T): T = {
    val apiService = new TestApiService(new MockAgoraDAO, new MockGoogleServicesDAO, new MockOntologyDAO, new MockRawlsDAO, new MockSearchDAO, new MockThurloeDAO)
    testCode(apiService)
  }

  "NihApiService" should "return NotFound when GET-ting a profile with no NIH username" in withDefaultApiServices { services =>
    Get("/nih/status") ~> dummyUserIdHeaders(services.thurloeDao.normalUserId) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }
  }

  it should "return NotFound when GET-ting a non-existent profile" in withDefaultApiServices { services =>
    Get("/nih/status") ~> dummyUserIdHeaders("userThatDoesntExist") ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }
  }

  it should "return BadRequest when NIH linking with an invalid JWT" in withDefaultApiServices { services =>
    Post("/nih/callback", JWTWrapper("bad-token")) ~> dummyUserIdHeaders(services.thurloeDao.normalUserId) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(BadRequest)
    }
    assert(!services.rawlsDao.groups(dbGapAuthorizedUsersGroupName).contains(services.thurloeDao.normalUserId))
  }

  it should "return OK when NIH linking with a valid JWT and the user is on the whitelist" in withDefaultApiServices { services =>
    assert(!services.rawlsDao.groups(dbGapAuthorizedUsersGroupName).contains(services.thurloeDao.normalUserId))
    Post("/nih/callback", validJwtOnWhitelist) ~> dummyUserIdHeaders(services.thurloeDao.normalUserId) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }
    assert(services.rawlsDao.groups(dbGapAuthorizedUsersGroupName).contains(services.thurloeDao.normalUserId))
  }

  it should "return OK when NIH linking with a valid JWT and the user is NOT on the whitelist" in withDefaultApiServices { services =>
    Post("/nih/callback", validJwtNotOnWhitelist) ~> dummyUserIdHeaders(services.thurloeDao.normalUserId) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }
    assert(!services.rawlsDao.groups(dbGapAuthorizedUsersGroupName).contains(services.thurloeDao.normalUserId))
  }

  it should "return OK when an expired user re-links. their new link time should be 30 days in the future" in withDefaultApiServices { services =>
    //verify that their link is indeed already expired
    Get("/nih/status") ~> dummyUserIdHeaders(services.thurloeDao.linkedButExpiredUserId) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(responseAs[NihStatus].linkExpireTime.get < DateUtils.now)
    }

    //link them using a valid JWT for a user on the whitelist
    Post("/nih/callback", validJwtOnWhitelist) ~> dummyUserIdHeaders(services.thurloeDao.linkedButExpiredUserId) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }

    //verify that their link expiration has been updated
    Get("/nih/status") ~> dummyUserIdHeaders(services.thurloeDao.linkedButExpiredUserId) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      val linkExpireTime = responseAs[NihStatus].linkExpireTime.get

      assert(linkExpireTime >= DateUtils.nowMinus1Hour) //link expire time is fresh
      assert(linkExpireTime <= DateUtils.nowPlus30Days) //link expire time is approx 30 days in the future
    }
    assert(services.rawlsDao.groups(dbGapAuthorizedUsersGroupName).contains(services.thurloeDao.linkedButExpiredUserId))
  }

  /* Test scenario:
     1 user that is linked but their TCGA access has expired. they should be removed
     1 user that is linked but they have no expiration date stored in their profile. they should be removed
     1 user that is linked and has active TCGA access. they should remain in the dbGapAuthorizedUsers group
   */
  it should "return NoContent and properly sync the whitelist for users of different link statuses" in withDefaultApiServices { services =>
    Post("/sync_whitelist") ~> sealRoute(services.syncRoute) ~> check {
      status should equal(NoContent)
    }
    assert(services.rawlsDao.groups(dbGapAuthorizedUsersGroupName).contains(services.thurloeDao.linkedUserId))
  }



}
