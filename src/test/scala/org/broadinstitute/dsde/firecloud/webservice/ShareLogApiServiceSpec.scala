package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.ActorRefFactory
import org.broadinstitute.dsde.firecloud.dataaccess.ShareLogApiServiceSpecShareLogDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.model.ShareLog.ShareType
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, ShareLogService}
import spray.http.OAuth2BearerToken
import spray.http.StatusCodes._

final class ShareLogApiServiceSpec extends BaseServiceSpec with ShareLogApiService {
  final val sharingUser = UserInfo("fake1@gmail.com", OAuth2BearerToken(dummyToken), 3600L, "fake1")

  private def getUserHeaders(userId: String, email: String) = dummyUserIdHeaders(userId, dummyToken, email)

  private val getShareesPath = "/sharelog/sharees"

  private def makeGetShareesPath(shareType: ShareType.Value) = s"$getShareesPath?shareType=${shareType.toString}"

  val localShareLogDao = new ShareLogApiServiceSpecShareLogDAO

  override val shareLogServiceConstructor: () => ShareLogService = ShareLogService.constructor(app.copy(shareLogDAO = localShareLogDao))(sharingUser)

  override def actorRefFactory: ActorRefFactory = system

  "ShareLogApiService" - {
    "when getting all sharees" in {
      Get(getShareesPath)  ~> getUserHeaders("fake1", "fake1@gmail.com") ~> sealRoute(shareLogServiceRoutes) ~> check {
        assertResult(OK) { status }
      }
    }
    "when getting workspace sharees" in {
      Get(makeGetShareesPath(ShareType.WORKSPACE)) ~> getUserHeaders("fake1", "fake1@gmail.com") ~> sealRoute(shareLogServiceRoutes) ~> check {
        assertResult(OK) { status }
      }
    }
  }
}

