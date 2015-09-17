package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{GetEntitiesWithType, GetEntitiesWithTypeActor}
import org.slf4j.LoggerFactory
import spray.http.HttpMethods
import spray.routing._

class EntityServiceActor extends Actor with EntityService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

trait EntityService extends HttpService with PerRequestCreator with FireCloudDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  val routes = entityRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def entityRoutes: Route =
    pathPrefix("workspaces" / Segment / Segment) { (workspaceNamespace, workspaceName) =>
      val url = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
      path("entities_with_type") {
        get { requestContext =>
          perRequest(requestContext, Props(new GetEntitiesWithTypeActor(requestContext)),
            GetEntitiesWithType.ProcessUrl(url))
        }
      } ~
      pathPrefix("entities") {
        val entityUrl = url + "/entitites"
        pathEnd {
          passthrough(entityUrl, HttpMethods.GET)
        } ~
        path(Segment) { entityType =>
          passthrough(entityUrl + "/" + entityType, HttpMethods.GET)
        }
      }
    }
}
