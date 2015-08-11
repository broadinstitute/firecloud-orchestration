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

trait SubmissionService extends HttpService with FireCloudDirectives {

  val routes = postAndGetRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def postAndGetRoutes: Route =
    path("workspaces" / Segment / Segment / "submissions") { (workspaceNamespace, workspaceName) =>
      get { requestContext =>
          val getSubmissions: HttpRequest = Get(FireCloudConfig.Rawls.
            submissionsUrl(workspaceNamespace, workspaceName))
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(getSubmissions)
      }
    } ~
    path("workspaces" / Segment / Segment / "submissions") { (workspaceNamespace, workspaceName) =>
      post {
        entity(as[SubmissionIngest]) { submission => requestContext =>
          val postSubmission: HttpRequest = Post(FireCloudConfig.Rawls.
            submissionsUrl(workspaceNamespace, workspaceName), submission)
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(postSubmission)
        }
      }
    } ~
    path("workspaces" / Segment / Segment / "submissions" / Segment) {
      (workspaceNamespace, workspaceName, submissionId) =>
      get { requestContext =>
        val getSubmission: HttpRequest = Get(FireCloudConfig.Rawls.
          submissionByIdUrl(workspaceNamespace, workspaceName, submissionId))
        actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
          HttpClient.PerformExternalRequest(getSubmission)
      }
    }

}
