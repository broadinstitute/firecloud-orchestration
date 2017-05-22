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
  val tcgaDbGaPAuthorized = FireCloudConfig.Nih.whitelists.filter(_.name.equals("TCGA")).head.groupToSync
  val targetDbGaPAuthorized = FireCloudConfig.Nih.whitelists.filter(_.name.equals("TARGET")).head.groupToSync

  //JWT for NIH username "firecloud-dev"
  val firecloudDevJwt = JWTWrapper("eyJhbGciOiJIUzI1NiJ9.ZmlyZWNsb3VkLWRldg.NPXbSpTmAOUvJ1HX85TauAARnlMKfqBsPjumCC7zE7s")

  //JWT for NIH username "tcga-user"
  val tcgaUserJwt = JWTWrapper("eyJhbGciOiJIUzI1NiJ9.dGNnYS11c2Vy.js6xodKBskGhcvetna7N1t6ltZ71WJkgs1ucIwm0Mss")

  //JWT for NIH username "target-user"
  val targetUserJwt = JWTWrapper("eyJhbGciOiJIUzI1NiJ9.dGFyZ2V0LXVzZXI.afm1xC0XfV0c_V34cy727P2-rlQQNbFhCFkEsDKwqH8")

  //JWT for NIH username "not-on-whitelist" (don't ever add this to the mock whitelists in MockGoogleServicesDAO.scala)
  val validJwtNotOnWhitelist = JWTWrapper("eyJhbGciOiJIUzI1NiJ9.bm90LW9uLXdoaXRlbGlzdA.DayvfECuGAQsXx-MEwXiuQyq86Eqc3Lmn46_9BGs6t0")

  case class TestApiService(agoraDao: MockAgoraDAO, googleDao: MockGoogleServicesDAO, ontologyDao: MockOntologyDAO, rawlsDao: MockRawlsDAO, searchDao: MockSearchDAO, thurloeDao: MockThurloeDAO)(implicit val executionContext: ExecutionContext) extends ApiServices

  def withDefaultApiServices[T](testCode: TestApiService => T): T = {
    val apiService = new TestApiService(new MockAgoraDAO, new MockGoogleServicesDAO, new MockOntologyDAO, new MockRawlsDAO, new MockSearchDAO, new MockThurloeDAO)
    testCode(apiService)
  }

  "NihApiService" should "return NotFound when GET-ting a profile with no NIH username" in withDefaultApiServices { services =>
    Get("/nih/status") ~> dummyUserIdHeaders(services.thurloeDao.TCGA_AND_TARGET_UNLINKED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }
  }

  it should "return NotFound when GET-ting a non-existent profile" in withDefaultApiServices { services =>
    Get("/nih/status") ~> dummyUserIdHeaders("userThatDoesntExist") ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }
  }

  it should "return BadRequest when NIH linking with an invalid JWT" in withDefaultApiServices { services =>
    Post("/nih/callback", JWTWrapper("bad-token")) ~> dummyUserIdHeaders(services.thurloeDao.TCGA_AND_TARGET_UNLINKED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(BadRequest)
    }
    assert(!services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
    assert(!services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
  }

  it should "sync when user is on TCGA whitelist but not TARGET" in withDefaultApiServices { services =>
    assert(!services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TCGA_UNLINKED))
    assert(!services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TCGA_UNLINKED))
    Post("/nih/callback", tcgaUserJwt) ~> dummyUserIdHeaders(services.thurloeDao.TCGA_UNLINKED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }
    assert(!services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TCGA_UNLINKED))
    assert(services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TCGA_UNLINKED))
  }

  it should "sync when user is on TARGET whitelist but not TCGA" in withDefaultApiServices { services =>
    assert(!services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TARGET_UNLINKED))
    assert(!services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TARGET_UNLINKED))
    Post("/nih/callback", targetUserJwt) ~> dummyUserIdHeaders(services.thurloeDao.TARGET_UNLINKED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }
    assert(services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TARGET_UNLINKED))
    assert(!services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TARGET_UNLINKED))
  }

  it should "sync when user is on both the TARGET and TCGA whitelists" in withDefaultApiServices { services =>
    assert(!services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
    assert(!services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
    Post("/nih/callback", firecloudDevJwt) ~> dummyUserIdHeaders(services.thurloeDao.TCGA_AND_TARGET_UNLINKED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }
    assert(services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
    assert(services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
  }

  it should "link a valid NIH account that isn't on any whitelists but not sync them to TCGA or TARGET groups" in withDefaultApiServices { services =>
    Post("/nih/callback", validJwtNotOnWhitelist) ~> dummyUserIdHeaders(services.thurloeDao.TCGA_AND_TARGET_UNLINKED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }
    assert(!services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
    assert(!services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_UNLINKED))
  }

  it should "return OK when an expired user re-links. their new link time should be 30 days in the future" in withDefaultApiServices { services =>
    //verify that their link is indeed already expired
    Get("/nih/status") ~> dummyUserIdHeaders(services.thurloeDao.TCGA_AND_TARGET_LINKED_EXPIRED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(responseAs[NihStatus].linkExpireTime.get < DateUtils.now)
    }

    //link them using a valid JWT for a user on the whitelist
    Post("/nih/callback", firecloudDevJwt) ~> dummyUserIdHeaders(services.thurloeDao.TCGA_AND_TARGET_LINKED_EXPIRED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }

    //verify that their link expiration has been updated
    Get("/nih/status") ~> dummyUserIdHeaders(services.thurloeDao.TCGA_AND_TARGET_LINKED_EXPIRED) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      val linkExpireTime = responseAs[NihStatus].linkExpireTime.get

      assert(linkExpireTime >= DateUtils.nowMinus1Hour) //link expire time is fresh
      assert(linkExpireTime <= DateUtils.nowPlus30Days) //link expire time is approx 30 days in the future
    }
    assert(services.rawlsDao.groups(tcgaDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_LINKED_EXPIRED))
    assert(services.rawlsDao.groups(targetDbGaPAuthorized).contains(services.thurloeDao.TCGA_AND_TARGET_LINKED_EXPIRED))
  }

  /* Test scenario:
     1 user that is linked but their TCGA access has expired. they should be removed
     1 user that is linked but they have no expiration date stored in their profile. they should be removed
     1 user that is linked and has active TCGA access. they should remain in the dbGapAuthorizedUsers group
   */
  it should "return NoContent and properly sync the whitelist for users of different link statuses across whitelists" in withDefaultApiServices { services =>
    Post("/sync_whitelist") ~> sealRoute(services.syncRoute) ~> check {
      status should equal(NoContent)
    }
    assert(services.rawlsDao.groups(tcgaDbGaPAuthorized).equals(Set(services.thurloeDao.TCGA_AND_TARGET_LINKED, services.thurloeDao.TCGA_LINKED)))
    assert(services.rawlsDao.groups(targetDbGaPAuthorized).equals(Set(services.thurloeDao.TCGA_AND_TARGET_LINKED, services.thurloeDao.TARGET_LINKED)))
  }
}
