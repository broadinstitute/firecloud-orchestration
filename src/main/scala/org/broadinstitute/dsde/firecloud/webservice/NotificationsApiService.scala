package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives

trait NotificationsApiService extends FireCloudDirectives with StandardUserInfoDirectives {
  final private val ApiPrefix = "api/notifications"
  final private val General = "general"
  final private val Workspace = "workspace"
  final private val RawlsNotifications = FireCloudConfig.Rawls.notificationsUrl

  final val notificationsRoutes: Route =
    get {
      pathPrefix(separateOnSlashes(ApiPrefix)) {
        path(General) {
          passthrough(encodeUri(s"$RawlsNotifications/$General"), GET)
        } ~
          path(Workspace / Segment / Segment) { (namespace, name) =>
            passthrough(encodeUri(s"$RawlsNotifications/$Workspace/$namespace/$name"), GET)
          }
      }
    }
}
