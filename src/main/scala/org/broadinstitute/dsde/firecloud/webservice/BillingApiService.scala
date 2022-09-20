package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.utils.StreamingPassthrough

trait BillingApiService extends FireCloudDirectives with StreamingPassthrough {
  private val userBillingUrl = FireCloudConfig.Rawls.authUrl + "/user/billing"

  val billingServiceRoutes: Route =
    pathPrefix("billing") {
      // all paths under /api/billing pass through to the same path in Rawls
      streamingPassthrough(Uri.Path("/api/billing") -> Uri(FireCloudConfig.Rawls.authUrl + "/billing"))
    } ~
    path("user" / "billing" / Segment) { projectId =>
      delete {
        passthrough(s"$userBillingUrl/$projectId", DELETE)
      }
    }
}
