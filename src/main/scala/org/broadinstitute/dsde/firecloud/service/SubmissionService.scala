package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.HttpMethods._
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls._

trait SubmissionService extends FireCloudDirectives {
  // TODO Resolve https://broadinstitute.atlassian.net/browse/GAWB-2807
  val routes: Route = {
    path("submissions" / "queueStatus") {
      get {
        passthrough(submissionQueueStatusUrl, GET)
      }
    } ~
    pathPrefix("workspaces" / Segment/ Segment) { (namespace, name) =>
      path("submissionsCount") {
         get {
           passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissionsCount"), GET)
         }
      } ~
      pathPrefix("submissions") {
        pathEnd {
          (get | post) {
            extract(_.request.method) { method =>
              passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissions"), method)
            }
          }
        } ~
        path("validate") {
          post {
            passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissions/validate"), POST)
          }
        } ~
        pathPrefix(Segment) { submissionId =>
          pathEnd {
            (delete | get) {
              extract(_.request.method) { method =>
                passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissions/$submissionId"), method)
              }
            }
          } ~
          pathPrefix("workflows" / Segment) { workflowId =>
            pathEnd {
              get {
                extract(_.request.uri.query()) { query =>
                  passthrough(Uri(encodeUri(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId")).withQuery(query), GET)
                }
              }
            } ~
            pathPrefix("outputs") {
              get {
                passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId/outputs"), GET)
              }
            } ~
            pathPrefix("cost") {
              get {
                passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId/cost"), GET)
              }
            }
          }
        }
      }
    }
  }

}
