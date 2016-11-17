package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.core._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.http.{Uri, HttpMethods}
import spray.httpx.SprayJsonSupport._
import spray.routing._

trait EntityService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding with StandardUserInfoDirectives {

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor

  private implicit val executionContext = actorRefFactory.dispatcher
  lazy val log = LoggerFactory.getLogger(getClass)

  def entityRoutes: Route =
    requireUserInfo() { userInfo =>
      pathPrefix("api") {
        pathPrefix("workspaces" / Segment / Segment) { (workspaceNamespace, workspaceName) =>
          val baseRawlsEntitiesUrl = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
          path("entities_with_type") {
            get { requestContext =>
              perRequest(requestContext, Props(new GetEntitiesWithTypeActor(requestContext)),
                GetEntitiesWithType.ProcessUrl(encodeUri(baseRawlsEntitiesUrl)))
            }
          } ~
            pathPrefix("entities") {
              pathEnd {
                passthrough(requestCompression = true, baseRawlsEntitiesUrl, HttpMethods.GET)
              } ~
                path("copy") {
                  post {
                    entity(as[EntityCopyDefinition]) { copyRequest => requestContext =>
                      val copyMethodConfig = new EntityCopyWithDestinationDefinition(
                        sourceWorkspace = copyRequest.sourceWorkspace,
                        destinationWorkspace = WorkspaceName(Some(workspaceNamespace), Some(workspaceName)),
                        entityType = copyRequest.entityType,
                        entityNames = copyRequest.entityNames)
                      val extReq = Post(FireCloudConfig.Rawls.workspacesEntitiesCopyUrl, copyMethodConfig)
                      externalHttpPerRequest(requestContext, extReq)
                    }
                  }
                } ~
                //TODO: Disabled as part of GAWB-423, re-enable as part of GAWB-422
                //        path("delete") {
                //          post {
                //            entity(as[EntityDeleteDefinition]) { deleteDefinition => requestContext =>
                //              perRequest(requestContext, Props(new DeleteEntitiesActor(requestContext, deleteDefinition.entities)),
                //                DeleteEntities.ProcessUrl(baseRawlsEntitiesUrl))
                //            }
                //          }
                //        } ~
                pathPrefix(Segment) { entityType =>
                  val entityTypeUrl = encodeUri(baseRawlsEntitiesUrl + "/" + entityType)
                  pathEnd {
                    passthrough(requestCompression = true, entityTypeUrl, HttpMethods.GET)
                  } ~
                    parameters('attributeNames.?) { attributeNamesString =>
                      path("tsv") { requestContext =>
                        val filename = entityType + ".txt"
                        val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
                        perRequest(requestContext, ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo),
                          ExportEntitiesByTypeActor.ExportEntities(workspaceNamespace, workspaceName, filename, entityType, attributeNames))
                      }
                    } ~
                    path(Segment) { entityName =>
                      passthrough(requestCompression = true, entityTypeUrl + "/" + entityName, HttpMethods.GET, HttpMethods.PATCH, HttpMethods.DELETE)
                    }
                }
            } ~
            pathPrefix("entityQuery" / Segment) { entityType =>
              val baseRawlsEntityQueryUrl = FireCloudConfig.Rawls.entityQueryPathFromWorkspace(workspaceNamespace, workspaceName)
              val baseEntityQueryUri = Uri(baseRawlsEntityQueryUrl)

              pathEnd {
                get { requestContext =>
                  val requestUri = requestContext.request.uri

                  val entityQueryUri = baseEntityQueryUri
                    .withPath(baseEntityQueryUri.path ++ Uri.Path.SingleSlash ++ Uri.Path(entityType))
                    .withQuery(requestUri.query)

                  // we use externalHttpPerRequest instead of passthrough; passthrough does not handle query params well.
                  val extReq = Get(entityQueryUri)
                  externalHttpPerRequest(requestCompression = true, requestContext, extReq)
                }
              }
            }
        }
      }
    }
}
