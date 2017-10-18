package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.{HttpMethods, Uri}
import spray.routing._

trait NotificationsApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {
  private final val ApiPrefix = "api/notifications"
  private final val General = "general"
  private final val Workspace = "workspace"

  final val notificationsRoutes: Route = {
    get {
      pathPrefix(separateOnSlashes(ApiPrefix)) {
        unmatchedPath { remaining =>
          val encodedTargetUri = Uri(encodeUri(FireCloudConfig.Rawls.notificationsUrl + remaining))
          path(General) {
            passthrough(encodedTargetUri, HttpMethods.GET)
          } ~
          path(Workspace / Segment / Segment) { (_, _) =>
            passthrough(encodedTargetUri, HttpMethods.GET)
          }
        }
      }
    }
  }
}
