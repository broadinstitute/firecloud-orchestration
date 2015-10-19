package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.core.{ExportEntitiesByType, ExportEntitiesByTypeActor, GetEntitiesWithType, GetEntitiesWithTypeActor}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{EntityCopyDefinition, EntityCopyWithDestinationDefinition, WorkspaceName}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.slf4j.LoggerFactory
import spray.http.HttpMethods
import spray.httpx.SprayJsonSupport._
import spray.routing._

class EntityServiceActor extends Actor with EntityService {
  def actorRefFactory = context
  def receive = runRoute(routes)
}

object EntityService {
  val entitiesPath = "/workspaces/%s/%s/entities"
  val copyPath = "/workspaces/entities/copy"
  def entitiesPathFromWorkspace(workspaceNamespace: String, workspaceName: String): String = {
    FireCloudConfig.Rawls.authUrl + entitiesPath.format(workspaceNamespace, workspaceName)
  }
}

trait EntityService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding {

  private implicit val executionContext = actorRefFactory.dispatcher
  val routes = entityRoutes
  lazy val log = LoggerFactory.getLogger(getClass)

  def entityRoutes: Route =
    pathPrefix("workspaces" / Segment / Segment) { (workspaceNamespace, workspaceName) =>
      val baseRawlsEntitiesUrl = EntityService.entitiesPathFromWorkspace(
        workspaceNamespace, workspaceName)
      path("entities_with_type") {
        get { requestContext =>
          perRequest(requestContext, Props(new GetEntitiesWithTypeActor(requestContext)),
            GetEntitiesWithType.ProcessUrl(encodeUri(baseRawlsEntitiesUrl)))
        }
      } ~
      pathPrefix("entities") {
        pathEnd {
          passthrough(baseRawlsEntitiesUrl, HttpMethods.GET)
        } ~
        path("copy") {
          post {
            entity(as[EntityCopyDefinition]) { copyRequest => requestContext =>
              val copyMethodConfig = new EntityCopyWithDestinationDefinition(
                sourceWorkspace = copyRequest.sourceWorkspace,
                destinationWorkspace = WorkspaceName(Some(workspaceNamespace), Some(workspaceName)),
                entityType = copyRequest.entityType,
                entityNames = copyRequest.entityNames)
              val extReq = Post(FireCloudConfig.Rawls.authUrl + EntityService.copyPath,
                copyMethodConfig)
              externalHttpPerRequest(requestContext, extReq)
            }
          }
        } ~
        pathPrefix(Segment) { entityType =>
          val entityTypeUrl = encodeUri(baseRawlsEntitiesUrl + "/" + entityType)
          pathEnd {
            passthrough(entityTypeUrl, HttpMethods.GET)
          } ~
          path("tsv") { requestContext =>
            val filename = entityType + ".txt"
            perRequest(requestContext, Props(new ExportEntitiesByTypeActor(requestContext)),
              ExportEntitiesByType.ProcessEntities(entityTypeUrl, filename, entityType))
          } ~
          path(Segment) { entityName =>
            passthrough(entityTypeUrl + "/" + entityName, HttpMethods.GET, HttpMethods.PATCH, HttpMethods.DELETE)
          }
        }
      }
    }

}
