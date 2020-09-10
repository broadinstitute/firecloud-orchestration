package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import spray.http.HttpMethods._
import spray.routing._

trait BillingService extends HttpService with PerRequestCreator with FireCloudDirectives {
  private val billingUrl = FireCloudConfig.Rawls.authUrl + "/billing"
  private val userBillingUrl = FireCloudConfig.Rawls.authUrl + "/user/billing"

  val routes: Route =
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
          path("googleRole" / Segment / Segment) { (googleRole, email) =>
            (delete | put) {
              extract(_.request.method) { method =>
                passthrough(s"$billingUrl/$projectId/googleRole/$googleRole/$email", method)
              }
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
