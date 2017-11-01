package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.HttpMethods.GET
import spray.routing._

trait NotificationsApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {
  private final val ApiPrefix = "api/notifications"
  private final val General = "general"
  private final val Workspace = "workspace"
  private final val RawlsNotifications = FireCloudConfig.Rawls.notificationsUrl

  final val notificationsRoutes: Route = {
    get {
      pathPrefix(separateOnSlashes(ApiPrefix)) {
        path(General) {
          passthrough(s"$RawlsNotifications/$General", GET)
        } ~
        path(Workspace / Segment / Segment) { (namespace, name) =>
          passthrough(s"$RawlsNotifications/$Workspace/$namespace/$name", GET)
        }
      }
    }
  }
}
