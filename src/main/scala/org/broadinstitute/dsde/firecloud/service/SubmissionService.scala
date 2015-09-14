package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SubmissionIngest
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.httpx.SprayJsonSupport._
import spray.routing.{HttpService, Route}


abstract class SubmissionServiceActor extends Actor with SubmissionService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait SubmissionService extends HttpService with PerRequestCreator with FireCloudDirectives {

  val routes = postAndGetRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def postAndGetRoutes: Route =
    pathPrefix("workspaces" / Segment / Segment / "submissions") { (workspaceNamespace, workspaceName) =>
      val listSubmissionsPath = FireCloudConfig.Rawls.submissionsUrl(workspaceNamespace, workspaceName)
      pathEnd {
        passthrough(listSubmissionsPath, "get", "post")
      } ~
      pathPrefix(Segment) { submissionId =>
        pathEnd {
          passthrough(FireCloudConfig.Rawls.
            submissionByIdUrl(workspaceNamespace, workspaceName, submissionId), "get", "delete")
        } ~
        path("workflows" / Segment / "outputs") { workflowId =>
          passthrough(FireCloudConfig.Rawls.
            workflowOutputsByIdUrl(workspaceNamespace, workspaceName, submissionId, workflowId), "get")
        }
      }
    }
}
