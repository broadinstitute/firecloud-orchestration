package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Props, Actor}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SubmissionIngest
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.http.HttpRequest
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
    path("workspaces" / Segment / Segment / "submissions") { (workspaceNamespace, workspaceName) =>
      get { requestContext =>
          val extReq = Get(FireCloudConfig.Rawls.
            submissionsUrl(workspaceNamespace, workspaceName))
          externalHttpPerRequest(requestContext, extReq)
      }
    } ~
    path("workspaces" / Segment / Segment / "submissions") { (workspaceNamespace, workspaceName) =>
      post {
        entity(as[SubmissionIngest]) { submission => requestContext =>
          val extReq = Post(FireCloudConfig.Rawls.
            submissionsUrl(workspaceNamespace, workspaceName), submission)
          externalHttpPerRequest(requestContext, extReq)
        }
      }
    } ~
    path("workspaces" / Segment / Segment / "submissions" / Segment) {
      (workspaceNamespace, workspaceName, submissionId) =>
      get { requestContext =>
        val extReq = Get(FireCloudConfig.Rawls.
          submissionByIdUrl(workspaceNamespace, workspaceName, submissionId))
        externalHttpPerRequest(requestContext, extReq)
      }
    } ~
    path("workspaces" / Segment / Segment / "submissions" / Segment) {
      (workspaceNamespace, workspaceName, submissionId) =>
        delete { requestContext =>
          val extReq: HttpRequest = Delete(FireCloudConfig.Rawls.
            submissionByIdUrl(workspaceNamespace, workspaceName, submissionId))
          externalHttpPerRequest(requestContext, extReq)
        }
    } ~
    path("workspaces" / Segment / Segment / "submissions" / Segment / "workflows" / Segment / "outputs") {
      (workspaceNamespace, workspaceName, submissionId, workflowId) =>
        get { requestContext =>
          val extReq = Get(FireCloudConfig.Rawls.
            workflowOutputsByIdUrl(workspaceNamespace, workspaceName, submissionId, workflowId))
          externalHttpPerRequest(requestContext, extReq)
        }
    }

}
