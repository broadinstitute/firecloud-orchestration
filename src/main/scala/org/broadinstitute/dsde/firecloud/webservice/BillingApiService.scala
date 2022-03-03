package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives

trait BillingApiService extends FireCloudDirectives {
  private val billingUrl = FireCloudConfig.Rawls.authUrl + "/billing"
  private val userBillingUrl = FireCloudConfig.Rawls.authUrl + "/user/billing"
  private val v2BillingUrl = FireCloudConfig.Rawls.authUrl + "/billing/v2"

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
        } ~
        pathPrefix("v2") {
          pathEnd {
            post {
              passthrough(v2BillingUrl, POST)
            }
          } ~
            pathPrefix(Segment) { projectId =>
              pathEnd {
                (get | delete) {
                  extract(_.request.method) { method =>
                    passthrough(s"$v2BillingUrl/$projectId", method)
                  }
                }
              } ~
                pathPrefix("members") {
                  pathEnd {
                    get {
                      passthrough(s"$v2BillingUrl/$projectId/members", GET)
                    }
                  } ~
                    // workbench project role: owner or user
                    path(Segment / Segment) { (workbenchRole, email) =>
                      (delete | put) {
                        extract(_.request.method) { method =>
                          passthrough(s"$v2BillingUrl/$projectId/members/$workbenchRole/$email", method)
                        }
                      }
                    }
                } ~
                path("billingAccount") {
                  (put | delete) {
                    extract(_.request.method) { method =>
                      passthrough(s"$v2BillingUrl/$projectId/billingAccount", method)
                    }
                  }
                } ~
                path("spendReportConfiguration") {
                  (get | put | delete) {
                    extract(_.request.method) { method =>
                      passthrough(s"$v2BillingUrl/$projectId/spendReportConfiguration", method)
                    }
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
