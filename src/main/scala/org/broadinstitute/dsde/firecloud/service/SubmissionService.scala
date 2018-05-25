package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import spray.http.HttpMethods.{GET, POST}
import spray.routing.{HttpService, Route}
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls._

abstract class SubmissionServiceActor extends Actor with SubmissionService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait SubmissionService extends HttpService with PerRequestCreator with FireCloudDirectives {
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
                passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId"), GET)
              }
            } ~
            pathPrefix("outputs") {
              get {
                passthrough(encodeUri(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId/outputs"), GET)
              }
            } ~
            pathPrefix("cost") {
              get {
                passthrough(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId/cost", GET)
              }
            }
          }
        }
      }
    }
  }

}
