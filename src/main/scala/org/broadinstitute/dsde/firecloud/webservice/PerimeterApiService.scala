package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods.PUT
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives

trait PerimeterApiService extends FireCloudDirectives {
  private val perimetersUrl = FireCloudConfig.Rawls.authUrl + "/servicePerimeters"

  val perimeterServiceRoutes: Route =
    path("servicePerimeters" / Segment / "projects" / Segment) { (servicePerimeterName, projectName) =>
      put {
        passthrough(s"$perimetersUrl/$servicePerimeterName/projects/$projectName", PUT)
      }
    }
}
