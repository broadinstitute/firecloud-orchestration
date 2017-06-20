package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import better.files.File
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.rawls.model.{EntityCopyDefinition, WorkspaceName}
import org.slf4j.LoggerFactory
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Success, Try}

trait EntityService extends HttpService with PerRequestCreator with FireCloudDirectives
  with FireCloudRequestBuilding with StandardUserInfoDirectives with LazyLogging {

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
              get { requestContext =>
                val extReq = Get(Uri(baseRawlsEntitiesUrl))
                externalHttpPerRequest(requestContext, extReq)
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
                post { requestContext =>
                  val extReq = Post(Uri(baseRawlsEntitiesUrl + "/delete"))
                  externalHttpPerRequest(requestContext, extReq)
                }
              } ~
              pathPrefix(Segment) { entityType =>
                val entityTypeUrl = encodeUri(baseRawlsEntitiesUrl + "/" + entityType)
                pathEnd {
                  get { requestContext =>
                    val extReq = Get(Uri(entityTypeUrl))
                    externalHttpPerRequest(requestContext, extReq)
                  }
                } ~
                  parameters('attributeNames.?) { attributeNamesString =>
                    path("tsv") {
                      requireUserInfo() { userInfo => requestContext =>
                        lazy val attributeNames = attributeNamesString.map(_.split(",").toIndexedSeq)
                        val exportProps: Props = ExportEntitiesByTypeActor.props(exportEntitiesByTypeConstructor, userInfo)
                        val exportMessage = ExportEntitiesByTypeActor.ExportEntities(requestContext, workspaceNamespace, workspaceName, entityType, attributeNames)
                        val exportActor = actorRefFactory.actorOf(exportProps)
                        // Necessary for the actor ask pattern
                        implicit val timeout: Timeout = 1.minutes
                        lazy val fileFuture = (exportActor ? exportMessage).mapTo[File]
                        onComplete(fileFuture) {
                          case Success(file) =>
                            val httpEntity = file.contentType match {
                              case Some(cType) if cType.contains("text") => HttpEntity(ContentTypes.`text/plain`, file.contentAsString)
                              case _ => HttpEntity(ContentTypes.`application/octet-stream`, file.loadBytes)
                            }
                            complete(HttpResponse(
                              status = StatusCodes.OK,
                              entity = httpEntity,
                              headers = List(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> file.name)))))
                          case _ =>
                            complete(StatusCodes.InternalServerError, "Error generating entity download")
                        }.apply(requestContext)
                      }
                    }
                  } ~
                  path(Segment) { entityName =>
                    val uri = Uri(entityTypeUrl + "/" + entityName)
                    get { requestContext =>
                      externalHttpPerRequest(requestContext, Get(uri))
                    }
                    patch { requestContext =>
                      externalHttpPerRequest(requestContext, Patch(uri))
                    }
                    delete { requestContext =>
                      externalHttpPerRequest(requestContext, Delete(uri))
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
