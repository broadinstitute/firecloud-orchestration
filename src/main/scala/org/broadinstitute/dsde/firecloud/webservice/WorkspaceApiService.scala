package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat

import akka.actor.Props
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, PermissionReportService, WorkspaceService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.{EntityClient, FireCloudConfig}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.slf4j.{Logger, LoggerFactory}
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.http._
import spray.httpx.unmarshalling._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import scala.concurrent.ExecutionContext

trait WorkspaceApiService extends HttpService with FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  lazy val log: Logger = LoggerFactory.getLogger(getClass)
  lazy val rawlsWorkspacesRoot: String = FireCloudConfig.Rawls.workspacesUrl

  val workspaceServiceConstructor: WithAccessToken => WorkspaceService
  val permissionReportServiceConstructor: UserInfo => PermissionReportService
  val entityClientConstructor: (RequestContext, ModelSchema) => EntityClient

  private val filename = "-workspace-attributes.tsv"

  val workspaceRoutes: Route =
    pathPrefix("cookie-authed") {
      path("workspaces" / Segment / Segment / "exportAttributesTSV") {
          (workspaceNamespace, workspaceName) =>
            cookie("FCtoken") { tokenCookie =>
                mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.content)).apply(r)) { requestContext =>
                    perRequest(requestContext,
                        WorkspaceService.props(workspaceServiceConstructor, new AccessToken(OAuth2BearerToken(tokenCookie.content))),
                        WorkspaceService.ExportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, workspaceName + filename))
                  }
              }
        }
    } ~
    path("version" / "executionEngine") {
      passthrough(FireCloudConfig.Rawls.executionEngineVersionUrl, HttpMethods.GET)
    } ~
    pathPrefix("api") {
      pathPrefix("workspaces") {
        pathEnd {
          requireUserInfo() { _ =>
            extract(_.request.uri.query) { query =>
              passthrough(Uri(rawlsWorkspacesRoot).withQuery(query), HttpMethods.GET, HttpMethods.POST)
            }
          }
        } ~
        pathPrefix("tags") {
          pathEnd {
            requireUserInfo() { _ =>
              parameter('q.?) { queryString =>
                val baseUri = Uri(rawlsWorkspacesRoot + "/tags")
                val uri = queryString match {
                  case Some(query) => baseUri.withQuery(("q", query))
                  case None => baseUri
                }
                passthrough(uri.toString, HttpMethods.GET)
              }
            }
          }
        } ~
        pathPrefix(Segment / Segment) { (workspaceNamespace, workspaceName) =>
          val workspacePath = encodeUri(rawlsWorkspacesRoot + "/%s/%s".format(workspaceNamespace, workspaceName))
          pathEnd {
            get {
              requireUserInfo() { _ =>
                extract(_.request.uri.query) { query =>
                  passthrough(Uri(workspacePath).withQuery(query), HttpMethods.GET)
                }
              }
            } ~
            delete {
              requireUserInfo() { userInfo => requestContext =>
                perRequest(requestContext,
                  WorkspaceService.props(workspaceServiceConstructor, userInfo),
                  WorkspaceService.DeleteWorkspace(workspaceNamespace, workspaceName)
                )
              }
            }
          } ~
          path("methodconfigs") {
            get {
              extract(_.request.uri.query) { query =>
                requireUserInfo() { _ =>
                  passthrough(Uri(workspacePath + "/methodconfigs").withQuery(query), HttpMethods.GET)
                }
              }
            } ~
            post {
              requireUserInfo() { _ =>
                entity(as[MethodConfiguration]) { methodConfig =>
                    if (!methodConfig.outputs.exists { param => param._2.value.startsWith("this.library:") || param._2.value.startsWith("workspace.library:")})
                      passthrough(workspacePath + "/methodconfigs", HttpMethods.GET, HttpMethods.POST)
                    else
                      complete(StatusCodes.Forbidden, ErrorReport("Methods and configurations can not create or modify library attributes"))
                }
              }
            }
          } ~
          path("flexibleImportEntities") {
            post {
              requireUserInfo() { _ =>
                formFields('entities) { entitiesTSV =>
                  respondWithJSON { requestContext =>
                    perRequest(requestContext, EntityClient.props(entityClientConstructor, requestContext, FlexibleModelSchema),
                      EntityClient.ImportEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV))
                  }
                }
              }
            }
          } ~
          path("importEntities") {
            post {
              requireUserInfo() { _ =>
                formFields('entities) { entitiesTSV =>
                  respondWithJSON { requestContext =>
                    perRequest(requestContext, EntityClient.props(entityClientConstructor, requestContext, FirecloudModelSchema),
                      EntityClient.ImportEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV))
                  }
                }
              }
            }
          } ~
          path("importBagit"){
            post {
              requireUserInfo() { userInfo =>
                entity(as[BagitImportRequest]) { bagitRq =>
                  respondWithJSON { requestContext =>
                    perRequest(requestContext, EntityClient.props(entityClientConstructor, requestContext, FirecloudModelSchema),
                      EntityClient.ImportBagit(workspaceNamespace, workspaceName, bagitRq))
                  }
                }
              }
            }
          } ~
          path("importPFB") {
            post {
              requireUserInfo() { userInfo =>
                entity(as[PfbImportRequest]) { pfbRequest =>
                  respondWithJSON { requestContext =>
                    perRequest(requestContext, EntityClient.props(entityClientConstructor, requestContext, FlexibleModelSchema),
                      EntityClient.ImportPFB(workspaceNamespace, workspaceName, pfbRequest, userInfo))
                  }
                }
              }
            }
          } ~
          path("updateAttributes") {
            patch {
              requireUserInfo() { userInfo: UserInfo =>
                entity(as[Seq[AttributeUpdateOperation]]) { replacementAttributes => requestContext =>
                  perRequest(requestContext,
                    WorkspaceService.props(workspaceServiceConstructor, userInfo),
                    WorkspaceService.UpdateWorkspaceAttributes(workspaceNamespace, workspaceName, replacementAttributes)
                  )

                }
              }
            }
          } ~
          path("setAttributes") {
            patch {
              requireUserInfo() { userInfo =>
                implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
                entity(as[AttributeMap]) { newAttributes => requestContext =>
                  perRequest(requestContext,
                    WorkspaceService.props(workspaceServiceConstructor, userInfo),
                    WorkspaceService.SetWorkspaceAttributes(workspaceNamespace, workspaceName, newAttributes))
                }
              }
            }
          } ~
          path("exportAttributesTSV") {
            get {
              requireUserInfo() { userInfo => requestContext =>
                perRequest(requestContext,
                  WorkspaceService.props(workspaceServiceConstructor, userInfo),
                  WorkspaceService.ExportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, workspaceName + filename))
              }
            }
          } ~
          path("importAttributesTSV") {
            post {
              requireUserInfo() { userInfo =>
                formFields('attributes) { attributesTSV =>
                  respondWithJSON { requestContext =>
                    perRequest(requestContext, WorkspaceService.props(workspaceServiceConstructor, userInfo),
                      WorkspaceService.ImportAttributesFromTSV(workspaceNamespace, workspaceName, attributesTSV)
                    )
                  }
                }
              }
            }
          } ~
          path("acl") {
            patch {
              requireUserInfo() { userInfo =>
                parameter('inviteUsersNotFound.?) { inviteUsersNotFound =>
                  entity(as[List[WorkspaceACLUpdate]]) { aclUpdates => requestContext =>
                    perRequest(requestContext,
                      WorkspaceService.props(workspaceServiceConstructor, userInfo),
                      WorkspaceService.UpdateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, userInfo.userEmail, userInfo.id, inviteUsersNotFound.getOrElse("false").toBoolean))
                  }
                }
              }
            } ~
            get {
              requireUserInfo() { _ =>
                passthrough(workspacePath + "/acl", HttpMethods.GET)
              }
            }
          } ~
          path("catalog") {
            get {
              requireUserInfo() { userInfo => requestContext =>
                perRequest(requestContext,
                  WorkspaceService.props(workspaceServiceConstructor, userInfo),
                  WorkspaceService.GetCatalog(workspaceNamespace, workspaceName, userInfo))
              }
            } ~
            patch {
              requireUserInfo() { userInfo =>
                entity(as[Seq[WorkspaceCatalog]]) { updates => requestContext =>
                  perRequest(requestContext,
                    WorkspaceService.props(workspaceServiceConstructor, userInfo),
                    WorkspaceService.UpdateCatalog(workspaceNamespace, workspaceName, updates, userInfo))
                }
              }
            }
          } ~
          path("checkBucketReadAccess") {
            requireUserInfo() { _ =>
              passthrough(workspacePath + "/checkBucketReadAccess", HttpMethods.GET)
            }
          } ~
          path("bucketOptions") {
            requireUserInfo() { _ =>
              passthrough(workspacePath + "/bucketOptions", HttpMethods.GET)
            }
          } ~
          path("sendChangeNotification") {
            requireUserInfo() { _ =>
              passthrough(workspacePath + "/sendChangeNotification", HttpMethods.POST)
            }
          } ~
          path("accessInstructions") {
            requireUserInfo() { _ =>
              passthrough(workspacePath + "/accessInstructions", HttpMethods.GET)
            }
          } ~
          path("clone") {
            post {
              requireUserInfo() { _ =>
                entity(as[WorkspaceRequest]) { createRequest => requestContext =>
                  // the only reason this is not a passthrough is because library needs to overwrite any publish and discoverableByGroups values
                  val extReq = Post(workspacePath + "/clone",
                    createRequest.copy(attributes = createRequest.attributes + (AttributeName("library","published") -> AttributeBoolean(false)) + (AttributeName("library","discoverableByGroups") -> AttributeValueEmptyList)))
                  externalHttpPerRequest(requestContext, extReq)
                }
              }
            }
          } ~
          path("lock") {
            requireUserInfo() { _ =>
              passthrough(workspacePath + "/lock", HttpMethods.PUT)
            }
          } ~
          path("unlock") {
            requireUserInfo() { _ =>
              passthrough(workspacePath + "/unlock", HttpMethods.PUT)
            }
          } ~
          path("bucketUsage") {
            passthrough(workspacePath + "/bucketUsage", HttpMethods.GET)
          } ~
          path("storageCostEstimate") {
            get {
              requireUserInfo() { userInfo => requestContext =>
                perRequest(requestContext,
                  WorkspaceService.props(workspaceServiceConstructor, userInfo),
                  WorkspaceService.GetStorageCostEstimate(workspaceNamespace, workspaceName))
              }
            }
          } ~
          path("tags") {
            requireUserInfo() { userInfo =>
              get { requestContext =>
                perRequest(requestContext,
                  WorkspaceService.props(workspaceServiceConstructor, userInfo),
                  WorkspaceService.GetTags(workspaceNamespace, workspaceName))
              } ~
              put {
                entity(as[List[String]]) { tags => requestContext =>
                  perRequest(requestContext,
                    WorkspaceService.props(workspaceServiceConstructor, userInfo),
                    WorkspaceService.PutTags(workspaceNamespace, workspaceName, tags))
                }
              } ~
              patch {
                entity(as[List[String]]) { tags => requestContext =>
                  perRequest(requestContext,
                    WorkspaceService.props(workspaceServiceConstructor, userInfo),
                    WorkspaceService.PatchTags(workspaceNamespace, workspaceName, tags))
                }
              } ~
              delete {
                entity(as[List[String]]) { tags => requestContext =>
                  perRequest(requestContext,
                    WorkspaceService.props(workspaceServiceConstructor, userInfo),
                    WorkspaceService.DeleteTags(workspaceNamespace, workspaceName, tags))
                }
              }
            }
          } ~
          path("permissionReport") {
            requireUserInfo() { userInfo =>
              post {
                entity(as[PermissionReportRequest]) { reportInput =>
                  requestContext =>
                    perRequest(requestContext,
                      PermissionReportService.props(permissionReportServiceConstructor, userInfo),
                      PermissionReportService.GetPermissionReport(workspaceNamespace, workspaceName, reportInput))
                }
              }
            }
          }
        }
      }
    }
}
