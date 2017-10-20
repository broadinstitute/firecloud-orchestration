package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import spray.http.HttpMethods._
import spray.routing._

trait BillingService extends HttpService with PerRequestCreator with FireCloudDirectives {
  private val billingUrl = FireCloudConfig.Rawls.authUrl + "/billing"
  
  val routes: Route =
    pathPrefix("billing") {
      pathEnd {
        post {
          passthrough(s"$billingUrl", POST)
        }
      } ~
      pathPrefix(Segment) { projectId =>
        path("members") {
          get {
            passthrough(s"$billingUrl/$projectId/members", GET)
          }
        } ~
        path(Segment / Segment) { (role, email) =>
          (delete | put) {
            extract(_.request.method) { method =>
              passthrough(s"$billingUrl/$projectId/$role/$email", method)
            }
          }
        }
      }
    }
}
