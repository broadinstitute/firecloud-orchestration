package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.core.{GetEntitiesWithType, GetEntitiesWithTypeActor}
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, HttpClient}
import org.slf4j.LoggerFactory
import spray.client.pipelining._
import spray.routing._

class EntityServiceActor extends Actor with EntityService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait EntityService extends HttpService with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  val routes = entityRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def entityRoutes: Route =
    path("workspaces" / Segment / Segment / "entities_with_type") {
      (workspaceNamespace, workspaceName) =>
        get { requestContext =>
          val url = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
          val ewtActor = actorRefFactory.actorOf(Props(new GetEntitiesWithTypeActor(requestContext)))
          ewtActor ! GetEntitiesWithType.ProcessUrl(url)
        }
    } ~
    path("workspaces" / Segment / Segment / "entities") {
      (workspaceNamespace, workspaceName) =>
        get { requestContext =>
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(Get(FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)))
        }
    } ~
    path("workspaces" / Segment / Segment / "entities" / Segment) {
      (workspaceNamespace, workspaceName, entityType) =>
        get { requestContext =>
          actorRefFactory.actorOf(Props(new HttpClient(requestContext))) !
            HttpClient.PerformExternalRequest(Get(s"${FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)}/$entityType"))
        }
    }

}
