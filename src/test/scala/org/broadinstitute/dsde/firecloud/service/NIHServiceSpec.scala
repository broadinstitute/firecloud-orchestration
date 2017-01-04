package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.NIHStatus
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.broadinstitute.dsde.firecloud.webservice.NihApiService
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._


class NIHServiceSpec extends BaseServiceSpec with NihApiService {

  def actorRefFactory = system
  val nihServiceConstructor:() => NihService2 = NihService2.constructor(app)
  val uniqueId = "1234"
  val dbGapPath = UserService.groupPath(FireCloudConfig.Nih.rawlsGroupName)

  before {
    reset()
  }

  val targetUri = "/nih/status"

  "NIHService" - {

    "when GET-ting a profile with no NIH username" - {
      "NotFound response is returned" in {
        thurloeDao.nextGetProfileResponse = Some(thurloeDao.testProfile)
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(nihRoutes) ~> check {
          status should equal(NotFound)
        }
      }
    }

    "when GET-ting a profile with missing lastLinkTime" - {
      "loginRequired is true" in {
        thurloeDao.nextGetProfileResponse = Some(thurloeDao.testProfile.copy(
          linkedNihUsername = Some("nihuser")
        ))
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(nihRoutes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(true)
        }
      }
    }

    // This is done after the previous test to ensure reset() is working.
    "when GET-ting a non-existent profile" - {
      "NotFound response is returned" in {
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(nihRoutes) ~> check {
          status should equal(NotFound)
        }
      }
    }

    "when GET-ting a profile with missing linkExpireTime" - {
      "loginRequired is true" in {
        thurloeDao.nextGetProfileResponse = Some(thurloeDao.testProfile.copy(
          linkedNihUsername = Some("nihuser"),
          lastLinkTime = Some(222)
        ))
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(nihRoutes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(true)
        }
      }
    }

    "when GET-ting a profile with recent lastLinkTime and future linkExpireTime" - {
      "loginRequired is false" in {

        val lastLinkTime = DateUtils.now
        val linkExpireTime = DateUtils.nowPlus30Days

        thurloeDao.nextGetProfileResponse = Some(thurloeDao.testProfile.copy(
          linkedNihUsername = Some("nihuser"),
          lastLinkTime = Some(lastLinkTime),
          linkExpireTime = Some(linkExpireTime)
        ))
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(nihRoutes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(false)
        }
      }
    }

    "when GET-ting a profile with recent lastLinkTime and an expired linkExpireTime" - {
      "loginRequired is true" in {

        val lastLinkTime = DateUtils.now
        val linkExpireTime = DateUtils.nowMinus24Hours

        thurloeDao.nextGetProfileResponse = Some(thurloeDao.testProfile.copy(
          linkedNihUsername = Some("nihuser"),
          lastLinkTime = Some(lastLinkTime),
          linkExpireTime = Some(linkExpireTime)
        ))
        Get(targetUri) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(nihRoutes) ~> check {
          status should equal(OK)
          val nihStatus = responseAs[NIHStatus]
          nihStatus.loginRequired shouldBe(true)
        }
      }
    }

  }
}
