package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import spray.http.HttpMethods.GET
import spray.routing.{HttpService, Route}

import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls._


abstract class SubmissionServiceActor extends Actor with SubmissionService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait SubmissionService extends HttpService with PerRequestCreator with FireCloudDirectives {
  val routes: Route = {
    path("submissions" / "queueStatus") {
      get {
        passthrough(submissionQueueStatusUrl, GET)
      }
    } ~
    pathPrefix("workspaces" / Segment / Segment / "submissions") { (namespace, name) =>
      pathEnd {
        (get | post) {
          extract(_.request.method) { method =>
            passthrough(s"$workspacesUrl/$namespace/$name/submissions", method)
          }
        }
      } ~
      path("validate") {
        post {
          extract(_.request.method) { method =>
            passthrough(s"$workspacesUrl/$namespace/$name/submissions/validate", method)
          }
        }
      } ~
      pathPrefix(Segment) { submissionId =>
        pathEnd {
          (delete | get) {
            extract(_.request.method) { method =>
              passthrough(s"$workspacesUrl/$namespace/$name/submissions/$submissionId", method)
            }
          }
        } ~
        pathPrefix("workflows" / Segment) { workflowId =>
          pathEnd {
            get {
              extract(_.request.method) { method =>
                passthrough(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId", method)
              }
            }
          } ~
          pathPrefix("outputs") {
            get {
              extract(_.request.method) { method =>
                passthrough(s"$workspacesUrl/$namespace/$name/submissions/$submissionId/workflows/$workflowId/outputs", method)
              }
            }
          }
        }
      }
    }
  }
}
