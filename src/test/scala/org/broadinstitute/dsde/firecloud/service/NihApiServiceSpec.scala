package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.JWTWrapper
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
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

  case class TestApiService(agoraDao: MockAgoraDAO, googleDao: MockGoogleServicesDAO, ontologyDao: MockOntologyDAO, consentDao: MockConsentDAO, rawlsDao: MockRawlsDAO, samDao: MockSamDAO, searchDao: MockSearchDAO, researchPurposeSupport: MockResearchPurposeSupport, thurloeDao: MockThurloeDAO, logitDao: MockLogitDAO, shareLogDao: MockShareLogDAO)(implicit val executionContext: ExecutionContext) extends ApiServices

  def withDefaultApiServices[T](testCode: TestApiService => T): T = {
    val apiService = TestApiService(new MockAgoraDAO, new MockGoogleServicesDAO, new MockOntologyDAO, new MockConsentDAO, new MockRawlsDAO, new MockSamDAO, new MockSearchDAO, new MockResearchPurposeSupport, new MockThurloeDAO, new MockLogitDAO, new MockShareLogDAO)
    testCode(apiService)
  }

  "NihApiService" should "return NotFound when GET-ting a profile with no NIH username" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_UNLINKED)

    Get("/nih/status") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }
  }

  it should "return NotFound when GET-ting a non-existent profile" in withDefaultApiServices { services =>
    Get("/nih/status") ~> dummyUserIdHeaders("userThatDoesntExist") ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }
  }

  it should "return BadRequest when NIH linking with an invalid JWT" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_UNLINKED)

    Post("/nih/callback", JWTWrapper("bad-token")) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(BadRequest)
      assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
      assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    }
  }

  it should "link and sync when user is on TCGA whitelist but not TARGET" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_UNLINKED)

    assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    Post("/nih/callback", tcgaUserJwt) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
      assert(services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    }
  }

  it should "link and sync when user is on TARGET whitelist but not TCGA" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TARGET_UNLINKED)

    assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    Post("/nih/callback", targetUserJwt) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
      assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    }
  }

  it should "link and sync when user is on both the TARGET and TCGA whitelists" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_UNLINKED)

    assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    Post("/nih/callback", firecloudDevJwt) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
      assert(services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    }
  }

  it should "link but not sync when user is on neither the TARGET nor the TCGA whitelist" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_UNLINKED)

    Post("/nih/callback", validJwtNotOnWhitelist) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
      assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    }
  }

  it should "return OK when an expired user re-links. their new link time should be 30 days in the future" in withDefaultApiServices { services =>
    //verify that their link is indeed already expired
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_LINKED_EXPIRED)

    Get("/nih/status") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(responseAs[NihStatus].linkExpireTime.get < DateUtils.now)
    }

    //link them using a valid JWT for a user on the whitelist
    Post("/nih/callback", firecloudDevJwt) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
    }

    //verify that their link expiration has been updated
    Get("/nih/status") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      val linkExpireTime = responseAs[NihStatus].linkExpireTime.get

      assert(linkExpireTime >= DateUtils.nowMinus1Hour) //link expire time is fresh
      assert(linkExpireTime <= DateUtils.nowPlus30Days) //link expire time is approx 30 days in the future
      assert(services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
      assert(services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    }
  }

  /* Test scenario:
     1 user that is linked but their TCGA access has expired. they should be removed from the TCGA group
     1 user that is linked but their TARGET access has expired. they should be removed from the TARGET group
     1 user that is linked but they have no expiration date stored in their profile. they should be removed from the TCGA group
     1 user that is linked but their TCGA and TARGET access has expired. they should be removed from the TARGET and TCGA groups
     1 user that is linked and has active TCGA access. they should remain in the TCGA group
     1 user that is linked and has active TARGET access. they should remain in the TARGET group
     1 user that is linked and has active TARGET & TCGA access. they should remain in the TARGET and TCGA groups
   */
  it should "return NoContent and properly sync the whitelist for users of different link statuses across whitelists" in withDefaultApiServices { services =>
    Post("/sync_whitelist") ~> sealRoute(services.syncRoute) ~> check {
      status should equal(NoContent)
      assertSameElements(Set(services.thurloeDao.TCGA_AND_TARGET_LINKED, services.thurloeDao.TCGA_LINKED), services.samDao.groups(tcgaDbGaPAuthorized).map(_.value))
      assertSameElements(Set(services.thurloeDao.TCGA_AND_TARGET_LINKED, services.thurloeDao.TARGET_LINKED), services.samDao.groups(targetDbGaPAuthorized).map(_.value))
    }
  }

  it should "return NoContent and properly sync a single whitelist" in withDefaultApiServices { services =>
    Post("/sync_whitelist/TCGA") ~> sealRoute(services.syncRoute) ~> check {
      status should equal(NoContent)
      assertSameElements(Set(services.thurloeDao.TCGA_AND_TARGET_LINKED, services.thurloeDao.TCGA_LINKED), services.samDao.groups(tcgaDbGaPAuthorized).map(_.value))
    }
  }

  it should "return NotFound for unknown whitelist" in withDefaultApiServices { services =>
    Post("/sync_whitelist/foobar") ~> sealRoute(services.syncRoute) ~> check {
      status should equal(NotFound)
    }
  }
}
