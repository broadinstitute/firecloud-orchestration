package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, POST}
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives

trait BillingApiService extends FireCloudDirectives {
  private val billingUrl = FireCloudConfig.Rawls.authUrl + "/billing"
  private val userBillingUrl = FireCloudConfig.Rawls.authUrl + "/user/billing"

  val billingServiceRoutes: Route =
    pathPrefix("billing") {
      pathEnd {
        post {
          passthrough(billingUrl, POST)
        }
      } ~
        pathPrefix(Segment) { projectId =>
          path("members") {
            get {
              passthrough(s"$billingUrl/$projectId/members", GET)
            }
          } ~
            // workbench project role: owner or user
            path(Segment / Segment) { (workbenchRole, email) =>
              (delete | put) {
                extract(_.request.method) { method =>
                  passthrough(s"$billingUrl/$projectId/$workbenchRole/$email", method)
                }
              }
            }
        }
    } ~
      path("user" / "billing" / Segment) { projectId =>
        delete {
          passthrough(s"$userBillingUrl/$projectId", DELETE)
        }
      }
}
