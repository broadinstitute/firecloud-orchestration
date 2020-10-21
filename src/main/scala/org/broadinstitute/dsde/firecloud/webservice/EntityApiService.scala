package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.dataaccess.DsdeHttpDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.rawls.model.{EntityCopyDefinition, WorkspaceName}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.util.Try

trait EntityApiService extends FireCloudDirectives
  with FireCloudRequestBuilding with StandardUserInfoDirectives with DsdeHttpDAO {

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
              //TODO: the model schema doesn't matter for this one. Ideally, make it Optional
              complete { entityServiceConstructor(FlexibleModelSchema).GetEntitiesWithType(workspaceNamespace, workspaceName, userInfo) }
            }
          }
        } ~
          pathPrefix("entities") {
            pathEnd {
              requireUserInfo() { _ =>
                passthrough(encodeUri(baseRawlsEntitiesUrl), HttpMethods.GET)
              }
            } ~
              path("copy") {
                post {
                  requireUserInfo() { userInfo =>
                    parameter('linkExistingEntities.?) { linkExistingEntities =>
                      entity(as[EntityCopyWithoutDestinationDefinition]) { copyRequest =>
                        val linkExistingEntitiesBool = Try(linkExistingEntities.getOrElse("false").toBoolean).getOrElse(false)
                          val copyMethodConfig = new EntityCopyDefinition(
                            sourceWorkspace = copyRequest.sourceWorkspace,
                            destinationWorkspace = WorkspaceName(workspaceNamespace, workspaceName),
                            entityType = copyRequest.entityType,
                            entityNames = copyRequest.entityNames)
                          val extReq = Post(FireCloudConfig.Rawls.workspacesEntitiesCopyUrl(linkExistingEntitiesBool), copyMethodConfig)


                          complete { executeRequestRaw(userInfo.accessToken)(extReq) }
                      }
                    }
                  }
                }
              } ~
              path("delete") {
                post {
                  passthrough(encodeUri(baseRawlsEntitiesUrl + "/delete"), HttpMethods.POST)
                }
              } ~
              pathPrefix(Segment) { entityType =>
                val entityTypeUrl = encodeUri(baseRawlsEntitiesUrl + "/" + entityType)
                pathEnd {
                  requireUserInfo() { _ =>
                    passthrough(entityTypeUrl, HttpMethods.GET)
                  }
                } ~
                  pathPrefix(Segment) { entityName =>
                    pathEnd {
                      requireUserInfo() { _ =>
                        passthrough(entityTypeUrl + "/" + entityName, HttpMethods.GET, HttpMethods.PATCH)
                      }
                    } ~
                    path("evaluate") {
                      requireUserInfo() { _ =>
                        passthrough(entityTypeUrl + "/" + entityName + "/evaluate", HttpMethods.POST)
                      }
                    }
                  }
              }
          } ~
          pathPrefix("entityQuery" / Segment) { entityType =>
            pathEnd {
              get {
                requireUserInfo() { userInfo => requestContext =>
                  val requestUri = requestContext.request.uri
                  val entityQueryUri = FireCloudConfig.Rawls.
                    entityQueryUriFromWorkspaceAndQuery(workspaceNamespace, workspaceName, entityType).
                    withQuery(requestUri.query())
                  val extReq = Get(entityQueryUri)

                  executeRequestRaw(userInfo.accessToken)(extReq).flatMap { resp =>
                    requestContext.complete(resp)
                  }
                }
              }
            }
          }
      }
    }
}
