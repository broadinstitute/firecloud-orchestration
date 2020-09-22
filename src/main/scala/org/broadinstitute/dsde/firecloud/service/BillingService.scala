package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.server.Route

trait BillingService extends FireCloudDirectives {
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
