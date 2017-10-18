package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.{HttpMethods, Uri}
import spray.routing._

trait NotificationsApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private final val ApiPrefix = "notifications"
  private final val ApiPrefix2 = "api/notifications"
  private final val General = "general"
  private final val RequestCompression = true

//  val notificationsRoutes: Route =
//    pathPrefix("api") {
//      passthroughAllPaths(ApiPrefix, FireCloudConfig.Rawls.notificationsUrl)
//    }

  final val notificationsRoutes2: Route = {
    get {
      pathPrefix(separateOnSlashes(ApiPrefix2)) {
        unmatchedPath { remaining =>
          //println(FireCloudConfig.Rawls.notificationsUrl)
          path(General) {
            val targetPath = FireCloudConfig.Rawls.notificationsUrl + remaining
            passthrough(RequestCompression, Uri(encodeUri(targetPath)).toString, HttpMethods.GET)
          }
        }
      }
    }
  }
}
