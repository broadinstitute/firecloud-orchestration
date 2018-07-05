package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.ShareLog
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator, ShareLogService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.httpx.SprayJsonSupport
import spray.routing.{HttpService, Route}

import scala.concurrent.ExecutionContextExecutor

trait ShareLogApiService extends HttpService with PerRequestCreator with FireCloudDirectives
  with StandardUserInfoDirectives with SprayJsonSupport {

  private implicit val executionContext: ExecutionContextExecutor = actorRefFactory.dispatcher
  val shareLogServiceConstructor: () => ShareLogService

  val shareLogApiServiceRoutes: Route = {
    pathPrefix("shareLog") {
      get {
        requireUserInfo() { userInfo =>
          // this endpoint can probably be removed unless needed for manual testing
          path("log" / Segment ) { (sharee) =>
            get { requestContext =>
              perRequest(requestContext,
                ShareLogService.props(shareLogServiceConstructor),
                ShareLogService.LogShare(userInfo.id, sharee, ShareLog.WORKSPACE))
            }
          } ~
          path("log") {
            get { requesContext =>
              perRequest(requesContext,
                ShareLogService.props(shareLogServiceConstructor),
                ShareLogService.GetShares(userInfo.id))
            }
          }
        }
      }
    }
  }

}
