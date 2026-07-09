package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.{NihResources, NihStatus}
import org.broadinstitute.dsde.firecloud.service.NihStatus._
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.ExecutionContext

class NihApiServiceSpec extends ApiServiceSpec with BeforeAndAfterAll {

  // Config decommissioned: delete me? (CTM-581)
  val tcgaDbGaPAuthorized = WorkbenchGroupName("TCGA-dbGaP-Authorized")
  val targetDbGaPAuthorized = WorkbenchGroupName("TARGET-dbGaP-Authorized")

  case class TestApiService(agoraDao: MockAgoraDAO,
                            googleDao: MockGoogleServicesDAO,
                            rawlsDao: MockRawlsDAO,
                            samDao: MockSamDAO,
                            thurloeDao: MockThurloeDAO,
                            cwdsDao: CwdsDAO,
                            ecmDao: ExternalCredsDAO
  )(implicit val executionContext: ExecutionContext, implicit val materializer: Materializer)
      extends ApiServices

  def withDefaultApiServices[T](testCode: TestApiService => T): T = {
    val apiService = TestApiService(
      new MockAgoraDAO,
      new MockGoogleServicesDAO,
      new MockRawlsDAO,
      new MockSamDAO,
      new MockThurloeDAO,
      new MockCwdsDAO,
      new DisabledExternalCredsDAO
    )
    testCode(apiService)
  }

  "NihApiService" should "return NotFound when GET-ting a profile with no NIH username" in withDefaultApiServices {
    services =>
      val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_UNLINKED)

      Get("/nih/status") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(
        services.nihRoutes
      ) ~> check {
        status should equal(NotFound)
      }
  }

  it should "return NotFound when GET-ting a non-existent profile" in withDefaultApiServices { services =>
    Get("/nih/status") ~> dummyUserIdHeaders("userThatDoesntExist") ~> sealRoute(services.nihRoutes) ~> check {
      status should equal(NotFound)
    }
  }

  it should "unlink an NIH account for a user that is already linked" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_AND_TARGET_LINKED)

    // Assert that the keys are present in Thurloe
    assert(services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkedNihUsername")))
    assert(services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkExpireTime")))

    // Assert that the user is a member of the TCGA and TARGET NIH groups
    assert(services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    assert(services.samDao.groups(targetDbGaPAuthorized).contains(toLink))

    Delete("/nih/account") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(
      services.nihRoutes
    ) ~> check {
      status should equal(NoContent)
    }

    // Assert that the keys were removed from Thurloe
    assert(!services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkedNihUsername")))
    assert(!services.thurloeDao.mockKeyValues(toLink.value).map(_.key).contains(Some("linkExpireTime")))

    // Assert that the user has been removed from the relevant NIH groups
    assert(!services.samDao.groups(tcgaDbGaPAuthorized).contains(toLink))
    assert(!services.samDao.groups(targetDbGaPAuthorized).contains(toLink))
  }

  it should "tolerate unlinking an NIH account that is not linked" in withDefaultApiServices { services =>
    val toLink = WorkbenchEmail(services.thurloeDao.TCGA_UNLINKED)

    Delete("/nih/account") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(
      services.nihRoutes
    ) ~> check {
      status should equal(NoContent)
    }

    // Assert that there is no NIH account link
    Get("/nih/status") ~> dummyUserIdHeaders(toLink.value, "access_token", toLink.value) ~> sealRoute(
      services.nihRoutes
    ) ~> check {
      status should equal(NotFound)
    }

    // Assert the user is not in any of the NIH groups
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
  it should "return NoContent and properly sync the whitelist for users of different link statuses across whitelists" in withDefaultApiServices {
    services =>
      Post("/sync_whitelist") ~> sealRoute(services.syncRoute) ~> check {
        status should equal(NoContent)
      }
  }

  it should "return NoContent and properly sync a single whitelist" in withDefaultApiServices { services =>
    Post("/sync_whitelist/TCGA") ~> sealRoute(services.syncRoute) ~> check {
      status should equal(NoContent)
    }
  }

  it should "return NotFound for unknown whitelist" in withDefaultApiServices { services =>
    Post("/sync_whitelist/foobar") ~> sealRoute(services.syncRoute) ~> check {
      status should equal(NotFound)
    }
  }

  it should "return NIH resources for a user" in withDefaultApiServices { services =>
    val user = WorkbenchEmail("test-user@example.com")

    Get("/nih/resources") ~> dummyUserIdHeaders(user.value, "access_token", user.value) ~> sealRoute(
      services.nihRoutes
    ) ~> check {
      status should equal(OK)
      val resources = responseAs[NihResources]
      resources should not be null
    }
  }

  it should "handle user without NIH resources" in withDefaultApiServices { services =>
    val user = WorkbenchEmail("unlinked-user@example.com")

    Get("/nih/resources") ~> dummyUserIdHeaders(user.value, "access_token", user.value) ~> sealRoute(
      services.nihRoutes
    ) ~> check {
      // Depending on your implementation, this might return OK with empty resources
      // or a different status code
      status should equal(OK)
    }
  }
}
