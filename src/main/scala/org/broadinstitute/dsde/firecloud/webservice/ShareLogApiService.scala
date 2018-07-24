package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.ShareLog.ShareType
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, PerRequestCreator, ShareLogService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.httpx.SprayJsonSupport
import spray.routing.{HttpService, Route}

import scala.concurrent.ExecutionContextExecutor

trait ShareLogApiService extends HttpService with PerRequestCreator with FireCloudDirectives
  with StandardUserInfoDirectives with SprayJsonSupport with FireCloudRequestBuilding {

  private implicit val executionContext: ExecutionContextExecutor = actorRefFactory.dispatcher
  val shareLogServiceConstructor: () => ShareLogService

  val shareLogServiceRoutes: Route = {
    pathPrefix("sharelog") {
      path("sharees" ) {
        get {
          parameter("shareType".?) { shareType =>
            requireUserInfo() { userInfo => requestContext =>
              perRequest(requestContext,
                ShareLogService.props(shareLogServiceConstructor),
                ShareLogService.GetSharees(userInfo.id, shareType.map(ShareType.withName)))
            }
          }
        }
      }
    }
  }

}
