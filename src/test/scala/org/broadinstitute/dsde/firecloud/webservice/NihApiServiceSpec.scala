package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.{MockGoogleServicesDAO, MockUtils, SamMockserverUtils}
import org.broadinstitute.dsde.firecloud.model.JWTWrapper
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.NihStatus
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.ExecutionContext

class NihApiServiceSpec extends ApiServiceSpec with BeforeAndAfterAll with SamMockserverUtils {

  // mockserver to return an enabled user from Sam
  var mockSamServer: ClientAndServer = _

  override def afterAll(): Unit = mockSamServer.stop()

  override def beforeAll(): Unit = {
    mockSamServer = startClientAndServer(MockUtils.samServerPort)
    returnEnabledUser(mockSamServer)
  }

  val tcgaDbGaPAuthorized = FireCloudConfig.Nih.whitelists.filter(_.name.equals("TCGA")).head.groupToSync
  val targetDbGaPAuthorized = FireCloudConfig.Nih.whitelists.filter(_.name.equals("TARGET")).head.groupToSync

  // These tokens were encoded using the private key that pairs with the public key in MockShibbolethDAO
  //JWT for NIH username "firecloud-dev"
  val firecloudDevJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJmaXJlY2xvdWQtZGV2IiwiaWF0IjoxNjE0ODc3MTk3MDB9.k2HVt74OedfgP_bVHSz6U-1c25_XRMw2v8YtuiPHWZUPdYdXR8qZRzYq9YIUI1wbWtr6M7_w1XgBC9ubl7aLFtOcm00CSFAYkTA23NvF3jzrW_qoCArUfYP5GfvUAsA-8RPn-jIOpT5xBWp6vnoTElddiujrZ3_ykToB0s2ZE_cpi2uRUl6SQvNxsWmVdnAKi84NvPHKNwb3Z8HCQ9WdMJ53K2a_ks8psviQao-RvtLUO2hZY4G8cPM581WpfhZ_FM61EHqGQlflJlOSYceI6tiKuKoqPHvWHUAEkd5TdUtee1FVVgLYVEq6hidACMFSsanhqCfmnt4bA7Wlfzyt3A")

  //JWT for NIH username "tcga-user"
  val tcgaUserJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJ0Y2dhLXVzZXIiLCJpYXQiOjE2MTQ4ODIzMDIwMH0.S7RrbT8dfNVuQ-KdKXwjyvMiLSiaKHtsX00l8FzXuTMzb1FmS7xDxQlf2ZMTX2-BW1KRb8Hc7zMZ57LaizjBV4A-IGbbminOcdkIxtBsnmUWrT_UZrrcQD7AiXObVJdNx80CaozggVaAkWzd2WC-E_QRNC1C3YbQqCdErHxrBaLKrE7mU7RevCLQybrLCdcWFaKrrY8Lyvp_0yAJ0yd1iB86cr2tMvne7VGDGOmAWrFBm0FPr5J1tjzVYdpU9dY_Dpcd1E9tnQ9dCqaOmlC13V5dzI1BDt5oM74iwiuqQ8HbvHhgYE1oFJismKieW6VHDlKggie82dfG_Z86ajBOzg")

  //JWT for NIH username "target-user"
  val targetUserJwt = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJ0YXJnZXQtdXNlciIsImlhdCI6MTYxNDg3NzUwNzAwfQ.pHJj6yt-lCowUp5cXP7UObU9yFsoQUOBfWX93jnnRBqPkIyEj2e5nKO_DMQl73oSj7WX3H_LVBExUbBUuFTjvJZ977nb6YouSg2IBqj3_bB8QGBrBqQT-ZlsoBfvQ8Q02pVSWBbppqueP4IqFdBgl8ot9pyEx2I_utpohL2VKwwQJrOE4IewGURxA1Ie8F-NIzpAIN2b2N2uV_dkeD5pM7DP7kHUpfnAdLlSkqKTj0pu_jVtdsdF29rWDaxU1uAoJN9YgkVtULaTZ3pTwrRE31WAvCIQhfBAF7CRzXRJwv9fubktiGC1mWeJ7eHH8wpOvysm7OL-kS0R7boNlA9qhA")

  //JWT for NIH username "not-on-whitelist" (don't ever add this to the mock whitelists in MockGoogleServicesDAO.scala)
  val validJwtNotOnWhitelist = JWTWrapper("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJlcmFDb21tb25zVXNlcm5hbWUiOiJub3Qtb24td2hpdGVsaXN0IiwiaWF0IjoxNjE0ODc3NTc4MDB9.WpDrgtui5mOgDc5WvdWYC-l6vljGVyRI7DbBnpRYm7QOq00VLU6FI5YzVFe1eyjnHIqdz_KkkQD604Bi3G1qdyzhk_KKFCSeT4k5in-zS4Em_I2rcyUFs9DeHyFqVrBMZK8eZM_oKtSs23AtwGJASQ-sMvfXeXLcjTFuLWUdeiQEYedj9oOOA93ne-5Kaw9V7sR1foX-ybLDDHfHuAwTN2Vnvpmz0Qlk5osvvv-NunCo4M6A4fQ2FQWjrCwXk8-1N4Wf06dgDJ7ymsw9HtwHhzctVDzodaVlVU_RaC2gtSOWeD5nPaAJ7h6aNmNeLRmNwzCBm3TyPDY-qznPVM0DRg")

  case class TestApiService(agoraDao: MockAgoraDAO, googleDao: MockGoogleServicesDAO, ontologyDao: MockOntologyDAO, consentDao: MockConsentDAO, rawlsDao: MockRawlsDAO, samDao: MockSamDAO, searchDao: MockSearchDAO, researchPurposeSupport: MockResearchPurposeSupport, thurloeDao: MockThurloeDAO, shareLogDao: MockShareLogDAO, importServiceDao: MockImportServiceDAO, shibbolethDao: MockShibbolethDAO)(implicit val executionContext: ExecutionContext, implicit val materializer: Materializer) extends ApiServices

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

  it should "link and sync when user relinks as a different user" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TARGET_UNLINKED)

    assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    Post("/nih/callback", targetUserJwt) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
      assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    }
    // notice tcgaUserJwt, not targetUserJwt as above
    Post("/nih/callback", tcgaUserJwt) ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(OK)
      assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
      assert(services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
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

  it should "return OK when an expired user re-links. their new link time should be in the future" in withDefaultApiServices { services =>
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

      assert(linkExpireTime >= DateUtils.now) //link expire time is fresh
      assert(services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
      assert(services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
    }
  }

  it should "unlink an NIH account for a user that is already linked" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_LINKED)

    //Assert that the keys are present in Thurloe
    assert(services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkedNihUsername")))
    assert(services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkExpireTime")))

    //Assert that the user is a member of the TCGA and TARGET NIH groups
    assert(services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    assert(services.samDao.groups(targetDbGaPAuthorized).contains(toLink))

    Delete("/nih/account")  ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NoContent)
    }

    //Assert that the keys were removed from Thurloe
    assert(!services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkedNihUsername")))
    assert(!services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkExpireTime")))

    //Assert that the user has been removed from the relevant NIH groups
    assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
  }

  it should "tolerate unlinking an NIH account that is not linked" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_UNLINKED)

    Delete("/nih/account")  ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NoContent)
    }

    //Assert that there is no NIH account link
    Get("/nih/status") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }

    //Assert the user is not in any of the NIH groups
    assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
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
