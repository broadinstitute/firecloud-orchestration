package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.JWTWrapper
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.NihStatus
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail

import scala.concurrent.ExecutionContext

class NihApiServiceSpec extends ApiServiceSpec {
  val tcgaDbGaPAuthorized = FireCloudConfig.Nih.whitelists.filter(_.name.equals("TCGA")).head.groupToSync
  val targetDbGaPAuthorized = FireCloudConfig.Nih.whitelists.filter(_.name.equals("TARGET")).head.groupToSync

  // These tokens were encoded using the private key that pairs with the public key in MockShibbolethDAO
  //JWT for NIH username "firecloud-dev"
  val firecloudDevJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJmaXJlY2xvdWQtZGV2IiwiaWF0IjoxNjEyMjE4NzE2fQ.rObTxJcplho3AxI6oENAr4Km8O8XThUNu_fWnHspsLtuOlbMIBr3Cxp7gTKzI0raLY5oKNuHal9f6q4QS4aYzUq4E8IbuWHAA51wSevFvv7kzGy9fmdl757pYDwHXEeTKoTQa4enlcGAL3VSIHYLSrEy-PhYo3om_sXcsX86oVW4ogn79CoF4dTrLTSu9f9MWrwIxkbtXb-OUQaUGY6Tzhw_qdthcUbvC1cww00D-Tfx13oKVTGCv4wMiPXIbSjObQMTFJRuBzRpFUy4OWyq0AihSvSrbT47kTU4bw21QKavJMVlylwpmV60na310okEoHsOeGgnA0HlsJI55M8WrQ")

  //JWT for NIH username "tcga-user"
  val tcgaUserJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJ0Y2dhLXVzZXIiLCJpYXQiOjE2MTIyMTg3MzZ9.JbKZ7vRnD9iACOM9SCCnccoLy5RV78PvOHqcctshBzVjNKXX2xOyFcAo9iEwLoEq478a-WprrxTmHz8JcKSacMIFHSAy1EAcfEl9bUnbnt9qNTUFPrVFCsBMABw2CutMFdNDp3YdkBWa5wkRieb6uOf5ml6tnwFL6T-3ZIysl04XG_bUwsRFqLiMYX9ilVlDTvu67p2HKkhwbIpxFMTlZYPkWTeGqEkiS1_e-NKbk0Oh9ipCqglVxCzyBlh8XnkpggjUJ6V6Jc5wHctdu3RyOw9M0VY56nVGgAM86u-Uy6L32iavu50xHW3htOHRhtUOfqA0jKvq77rnZ1eyykvAjA")

  //JWT for NIH username "target-user"
  val targetUserJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJ0YXJnZXQtdXNlciIsImlhdCI6MTYxMjIxODc1NH0.Tl5mNwExOcJMcWe3eEdihnUPOk5411Hi4DyVbjqqi3_RLy4TDtPr7h9RtUwHU7bvx7p1l0M78L9G1SqJiD_NB_GK8TttPLNncy9JLEeKYOglHA4ZI9BbBTEggym-axUherSZ8W2UuXzRruZ8K4NkVPyvJmmWc86iCdCTQd8ETxahwgGL5_PTvPtbg-XsASmjbrmAMN8r9_fhB_lU9ltRvbVCMmk34SNBQk8f5FISmW4zVfMVRy00Sx-UYASJzT5CJneiGAHNVGJCilFCs_626KP5YiVqg3tdEcz7O5Om0UDHQY07HZCaESO31FiVv1R3k1xy6rFzn_brwXWhMQGrYA")

  //JWT for NIH username "not-on-whitelist" (don't ever add this to the mock whitelists in MockGoogleServicesDAO.scala)
  val validJwtNotOnWhitelist = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJub3Qtb24td2hpdGVsaXN0IiwiaWF0IjoxNjEyMjE4ODExfQ.OnVGz_9QJ5MhQbnDgz85sUDpFuSpLP0iMZymUNyIQX6I9wB_Q64yNMS34uq3tX33vioJ4HELWHtUssARXRn8YUntPm5tQO_pTLKuklx8V3HQXlvB_lN7lPAHR8EHY3NhYQsdxHnWfra7ZetmiakKmTu8AH3Lc6WOFUh18j4L-6nUKUW8mLY4hDMtAiAYMi6SIy4eISuuOVH3305gbqDKZOqNGfvzM19Vq7Gk5_zqEdRrQ9wYr_kDRIm7Sgok6Cd6GEF5FzILGWdXh2YKhzd_nVZR4aMb82NWUYSLgULpjX55kGxYWYI9pLbNPp97hB9qIE3-vufYoBc7PRnsAkpNCw")

  case class TestApiService(agoraDao: MockAgoraDAO, googleDao: MockGoogleServicesDAO, ontologyDao: MockOntologyDAO, consentDao: MockConsentDAO, rawlsDao: MockRawlsDAO, samDao: MockSamDAO, searchDao: MockSearchDAO, researchPurposeSupport: MockResearchPurposeSupport, thurloeDao: MockThurloeDAO, shareLogDao: MockShareLogDAO, importServiceDao: MockImportServiceDAO, shibbolethDao: MockShibbolethDAO)(implicit val executionContext: ExecutionContext) extends ApiServices

  def withDefaultApiServices[T](testCode: TestApiService => T): T = {
    val apiService = TestApiService(new MockAgoraDAO, new MockGoogleServicesDAO, new MockOntologyDAO, new MockConsentDAO, new MockRawlsDAO, new MockSamDAO, new MockSearchDAO, new MockResearchPurposeSupport, new MockThurloeDAO, new MockShareLogDAO, new MockImportServiceDAO, new MockShibbolethDAO)
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
