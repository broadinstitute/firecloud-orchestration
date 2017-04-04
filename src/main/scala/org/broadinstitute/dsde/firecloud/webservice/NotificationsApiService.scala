package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.routing._

trait NotificationsApiService extends HttpService with PerRequestCreator with FireCloudDirectives with StandardUserInfoDirectives {

  private final val ApiPrefix = "notifications"

  val notificationsRoutes: Route =
    pathPrefix("api") {
      passthroughAllPaths(ApiPrefix, FireCloudConfig.Rawls.notificationsPath)
    }
}
