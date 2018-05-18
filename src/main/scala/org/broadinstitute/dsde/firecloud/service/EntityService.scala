package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.core._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model.{EntityCopyDefinition, WorkspaceName}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.http.{HttpMethods, Uri}
import spray.httpx.SprayJsonSupport._
import spray.routing._

import scala.util.Try

trait EntityService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding with StandardUserInfoDirectives {

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  def entityRoutes: Route =
    pathPrefix("api") {
      pathPrefix("workspaces" / Segment / Segment) { (workspaceNamespace, workspaceName) =>
        val baseRawlsEntitiesUrl = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
        path("entities_with_type") {
          get {
            requireUserInfo() { _ => requestContext =>
              perRequest(requestContext, Props(new GetEntitiesWithTypeActor(requestContext)),
                GetEntitiesWithType.ProcessUrl(encodeUri(baseRawlsEntitiesUrl)))
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
                  requireUserInfo() { _ =>
                    parameter('linkExistingEntities.?) { linkExistingEntities =>
                      entity(as[EntityCopyWithoutDestinationDefinition]) { copyRequest =>
                        val linkExistingEntitiesBool = Try(linkExistingEntities.getOrElse("false").toBoolean).getOrElse(false)
                        requestContext =>
                          val copyMethodConfig = new EntityCopyDefinition(
                            sourceWorkspace = copyRequest.sourceWorkspace,
                            destinationWorkspace = WorkspaceName(workspaceNamespace, workspaceName),
                            entityType = copyRequest.entityType,
                            entityNames = copyRequest.entityNames)
                          val extReq = Post(FireCloudConfig.Rawls.workspacesEntitiesCopyUrl(linkExistingEntitiesBool), copyMethodConfig)
                          externalHttpPerRequest(requestContext, extReq)
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
                        passthrough(entityTypeUrl + "/" + entityName, HttpMethods.GET, HttpMethods.PATCH, HttpMethods.DELETE)
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
                requireUserInfo() { _ => requestContext =>
                  val requestUri = requestContext.request.uri
                  val entityQueryUri = FireCloudConfig.Rawls.
                    entityQueryUriFromWorkspaceAndQuery(workspaceNamespace, workspaceName, entityType).
                    withQuery(requestUri.query)
                  val extReq = Get(entityQueryUri)
                  externalHttpPerRequest(requestContext, extReq)
                }
              }
            }
          }
      }
    }
}
