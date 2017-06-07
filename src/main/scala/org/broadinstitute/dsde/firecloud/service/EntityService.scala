package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.core._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model.{EntityCopyDefinition, WorkspaceName}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import scala.concurrent.duration._
import spray.http.{ContentTypes, HttpMethods, Uri}
import spray.httpx.SprayJsonSupport._
import spray.routing._
import scala.language.postfixOps

import scala.util.{Success, Try}

trait EntityService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding with StandardUserInfoDirectives {

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor

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
                passthrough(requestCompression = true, baseRawlsEntitiesUrl, HttpMethods.GET)
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
                    passthrough(requestCompression = true, entityTypeUrl, HttpMethods.GET)
                  }
                } ~
                  parameters('attributeNames.?) { attributeNamesString =>
                    path("tsv") {
                      requireUserInfo() { userInfo => requestContext =>
                        val (filename, contentType) = ModelSchema.getCollectionMemberType(entityType) match {
                          case Success(Some(collectionType)) => (entityType + ".zip", ContentTypes.`application/octet-stream`)
                          case _ => (entityType + ".txt", ContentTypes.`text/plain`)
                        }
                        val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
                        val actorProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo)
                        val streamOperation = ExportEntitiesByTypeActor.StreamEntities(requestContext, workspaceNamespace, workspaceName, filename, entityType, attributeNames)
                        val actor = actorRefFactory.actorOf(actorProps)
                        implicit val timeout = Timeout(10 minute)
                        val streamFuture = (actor ? streamOperation).mapTo[Stream[Array[Byte]]]
                        streamFuture.map { stream =>
                          actorRefFactory.actorOf(StreamingActor.props(requestContext, filename, contentType, stream))
                        }
                      }
                    }
                  } ~
                  path(Segment) { entityName =>
                    requireUserInfo() { _ =>
                      passthrough(requestCompression = true, entityTypeUrl + "/" + entityName, HttpMethods.GET, HttpMethods.PATCH, HttpMethods.DELETE)
                    }
                  }
              }
          } ~
          pathPrefix("entityQuery" / Segment) { entityType =>
            val baseRawlsEntityQueryUrl = FireCloudConfig.Rawls.entityQueryPathFromWorkspace(workspaceNamespace, workspaceName)
            val baseEntityQueryUri = Uri(baseRawlsEntityQueryUrl)

            pathEnd {
              get {
                requireUserInfo() { _ => requestContext =>
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
