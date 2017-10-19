package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import spray.http.HttpMethods.GET
import spray.routing.{HttpService, Route}

import org.broadinstitute.dsde.firecloud.FireCloudConfig


abstract class SubmissionServiceActor extends Actor with SubmissionService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait SubmissionService extends HttpService with PerRequestCreator with FireCloudDirectives {

  val routes: Route = {
    path("submissions" / "queueStatus") {
      get {
        passthrough(requestCompression = true, FireCloudConfig.Rawls.submissionQueueStatusUrl, GET)
      }
    } ~
    pathPrefix("workspaces" / Segment / Segment) { (namespace, name) =>
      pathPrefixTest("submissions") {
        val path = urlFormattedWith(namespace, name)
        passthroughAllPaths("submissions", path)
      }
    }
  }

  private def urlFormattedWith(workspaceNamespace: String, workspaceName: String) = {
    FireCloudConfig.Rawls.submissionsUrl.format(workspaceNamespace, workspaceName)
  }
}
