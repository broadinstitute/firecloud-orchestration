package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.{RestJsonClient, StandardUserInfoDirectives, StreamingPassthrough}
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.rawls.model.{EntityCopyDefinition, WorkspaceName}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.util.Try

trait EntityApiService
    extends FireCloudDirectives
    with StreamingPassthrough
    with FireCloudRequestBuilding
    with StandardUserInfoDirectives
    with RestJsonClient {

  implicit val executionContext: ExecutionContext
  lazy val log = LoggerFactory.getLogger(getClass)

  val entityServiceConstructor: (ModelSchema) => EntityService

  def entityRoutes: Route =
    pathPrefix("api") {
      pathPrefix("workspaces" / Segment / Segment) { (workspaceNamespace, workspaceName) =>
        val baseRawlsEntitiesUrl = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
        path("entities_with_type") {
          get {
            requireUserInfo() { userInfo =>
              // TODO: the model schema doesn't matter for this one. Ideally, make it Optional
              complete {
                entityServiceConstructor(FlexibleModelSchema).getEntitiesWithType(workspaceNamespace,
                                                                                  workspaceName,
                                                                                  userInfo
                )
              }
            }
          }
        } ~
          pathPrefix("entities") {
            path("copy") {
              post {
                requireUserInfo() { userInfo =>
                  parameter(Symbol("linkExistingEntities").?) { linkExistingEntities =>
                    entity(as[EntityCopyWithoutDestinationDefinition]) { copyRequest =>
                      val linkExistingEntitiesBool =
                        Try(linkExistingEntities.getOrElse("false").toBoolean).getOrElse(false)
                      val copyMethodConfig = new EntityCopyDefinition(
                        sourceWorkspace = copyRequest.sourceWorkspace,
                        destinationWorkspace = WorkspaceName(workspaceNamespace, workspaceName),
                        entityType = copyRequest.entityType,
                        entityNames = copyRequest.entityNames
                      )
                      val extReq = Post(FireCloudConfig.Rawls.workspacesEntitiesCopyUrl(linkExistingEntitiesBool),
                                        copyMethodConfig
                      )

                      complete(userAuthedRequest(extReq)(userInfo))
                    }
                  }
                }
              }
            }
          }
      }
    }
}
