package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.slf4j.LoggerFactory
import spray.http.HttpMethods
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
        passthrough(FireCloudConfig.Rawls.submissionQueueStatusUrl, HttpMethods.GET)
      }
    } ~
    pathPrefix("workspaces" / Segment / Segment) { (namespace, name) =>
      pathPrefixTest("submissions") {
        println(s"submissionUrl = ${FireCloudConfig.Rawls.submissionsUrl}")
        val path = urlFormattedWith(namespace, name)
        passthroughAllPaths("submissions", path)
      }
    }
  }

  private def urlFormattedWith(workspaceNamespace: String, workspaceName: String) = {
    FireCloudConfig.Rawls.submissionsUrl.format(workspaceNamespace, workspaceName)
  }
}
