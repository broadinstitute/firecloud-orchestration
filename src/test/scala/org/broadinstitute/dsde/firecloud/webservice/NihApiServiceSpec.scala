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
  val firecloudDevJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJmaXJlY2xvdWQtZGV2IiwiaWF0IjoxNjE0ODc3MTk3fQ.pf8rfvXkVgyGFukqIevkYJd-Y0b6ORkDjMtETKyFGonEttp1kur_RMeZqraZjU01vPhmQwCHFOJgmBobmUzkPM6nqOaYDqyJpKhmW8Dy632iCsSzJPliat1yZM6hy4WQdo0VztChVcEH70O2q3EwRCjV38MpjWhTyOgH9-olGsguCurJHF-vfCrCavvxRAmaafh4xO9oV1qU0k483P-H2yJgTm5lHdsQPbLiu-7H1yd8p34laJNBZum4i0Ie9oAafYhbaUOCYa-IjvnOFfaDQhW5rFK-GaXC1wConHUavPJfycM3at9mhhipNugTLMXEYig0f9v-X6z7zTaPmlFMGA")

  //JWT for NIH username "tcga-user"
  val tcgaUserJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJ0Y2dhLXVzZXIiLCJpYXQiOjE2MTQ4Nzc0MzZ9.FfrQWLwGwg0y2tzSRXtHMaoFUBbtfGKLfUDySnZ6YPU4d_3zNMgY0CWaRZJ02mnTGQN6ye7w1X4PSeY5uaO1mIz3oB3Ly9OL0LGCJmQYLA5q9V6TOBumwRtjPw6gLfE6fBP7ym5J5Gs-vphoGLrrHi8RMso4dJVKZZZVyQuGzboyfvmiAvFu5C1ucuCwROm0obZOOr36hynsHcB8fh4Y0PXbPUKcvNICyvWruuE9KrFJ_2O1lkhIlciB4SWhajt8T6LAG1TlSiuw-CcTxcH3i96bmjgK8vwRrM5EL4hT_XAzyHsbxFaZSgiRjYJBUrfU6dUTtGU6ad1HKYjfygWg6w")

  //JWT for NIH username "target-user"
  val targetUserJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJ0YXJnZXQtdXNlciIsImlhdCI6MTYxNDg3NzUwN30.lHsRqiD1ZQaxyneNCZsImQWcf7TXnZv6urEvajb0OqRFSuBIFOwPobTM0fWC8VDuQFepWYN3KaZLPPociQIqJ02ss81gDOEUEXVrwrbCJXuT6H4bvd6OrVyaa8Z9qyG30fG570OgxDUhQp2znvlVASgzVsREyNt1AnWtClXoetTNh5H1aHNG8PzRjAk3fHr0m_qqADR28OEzOmXVvCcn9x4A6xYA2RFZzUDUpYXjfRtH03lq-llQxYUq3eUQ7bcYim8Ydhq2nVxw1QAsA4jTnedjpKj53cQyozivsQMI_GvNtBKlCAl7RgbOK71J2F6p26rr14uGVSzOXTjTOvxY0w")

  //JWT for NIH username "not-on-whitelist" (don't ever add this to the mock whitelists in MockGoogleServicesDAO.scala)
  val validJwtNotOnWhitelist = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJub3Qtb24td2hpdGVsaXN0IiwiaWF0IjoxNjE0ODc3NTc4fQ.E24rsJSYcFEYGD6UbdaIIezejXsNAUvjlLNO5zFEncJhPV0RTp45Y3M-P8jo9UB2ySA2GDq-sAa7lvTXHg8fUM88Ps9h8bv0WlYcpKJ6r24gC0TPUBhudL_UqDzktttmeWNBkaksjWFEvekpp4a1wp9v46qr0U97beeDbINlSyR0uCY0mib9FBu52OwD0e0oN3JAEZEx8dbswL4pN-vlw9fVTKnw7P2DUeQqzMH6Na28QLBRFi2kQkY31UEXCiDTm1FGR492-r-u6p6Qzijm1chxvlWcgcY3A1TpNgqhP8BuWZrZIYSxOXnnNHhCA1VsEjUEvTTXO-AWXLDUl43tOA")

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
