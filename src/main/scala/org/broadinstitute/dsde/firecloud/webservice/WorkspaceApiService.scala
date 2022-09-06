package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpMethods, StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.FILETYPE_PFB
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, PermissionReportService, WorkspaceService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model._
import org.slf4j.{Logger, LoggerFactory}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext
import scala.util.Try

trait WorkspaceApiService extends FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  lazy val log: Logger = LoggerFactory.getLogger(getClass)
  lazy val rawlsWorkspacesRoot: String = FireCloudConfig.Rawls.workspacesUrl

  val workspaceServiceConstructor: WithAccessToken => WorkspaceService
  val permissionReportServiceConstructor: UserInfo => PermissionReportService
  val entityServiceConstructor: (ModelSchema) => EntityService

  private val filename = "-workspace-attributes.tsv"

  val workspaceRoutes: Route =
    pathPrefix("cookie-authed") {
      path("workspaces" / Segment / Segment / "exportAttributesTSV") {
        (workspaceNamespace, workspaceName) =>
          cookie("FCtoken") { tokenCookie =>
            mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.value)).apply(r)) {
              complete { workspaceServiceConstructor(new AccessToken(OAuth2BearerToken(tokenCookie.value))).exportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, workspaceName + filename) }
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
              extract(_.request.uri.query()) { query =>
                passthrough(Uri(rawlsWorkspacesRoot).withQuery(query), HttpMethods.GET, HttpMethods.POST)
              }
            }
          } ~
            pathPrefix("tags") {
              pathEnd {
                requireUserInfo() { _ =>
                  parameter(Symbol("q").?) { queryString =>
                    val baseUri = Uri(rawlsWorkspacesRoot + "/tags")
                    val uri = queryString match {
                      case Some(query) => baseUri.withQuery(Query(("q", query)))
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
                    extract(_.request.uri.query()) { query =>
                      passthrough(Uri(workspacePath).withQuery(query), HttpMethods.GET)
                    }
                  }
                } ~
                  delete {
                    requireUserInfo() { userInfo =>
                      complete { workspaceServiceConstructor(userInfo).deleteWorkspace(workspaceNamespace, workspaceName) }
                    }
                  }
              } ~
                path("methodconfigs") {
                  get {
                    extract(_.request.uri.query()) { query =>
                      requireUserInfo() { _ =>
                        passthrough(Uri(workspacePath + "/methodconfigs").withQuery(query), HttpMethods.GET)
                      }
                    }
                  } ~
                    post {
                      requireUserInfo() { userInfo =>
                        entity(as[MethodConfiguration]) { methodConfig =>
                          if (!methodConfig.outputs.exists { param => param._2.value.startsWith("this.library:") || param._2.value.startsWith("workspace.library:")}) {
                            val passthroughReq = Post(workspacePath + "/methodconfigs", methodConfig)
                            complete { userAuthedRequest(passthroughReq)(userInfo) }
                          } else {
                            complete(StatusCodes.Forbidden, ErrorReport("Methods and configurations can not create or modify library attributes"))
                          }
                        }
                      }
                    }
                } ~
                path("flexibleImportEntities") {
                  post {
                    requireUserInfo() { userInfo =>
                      parameter("async" ? "false") { asyncStr =>
                        parameter("deleteEmptyValues" ? "false") { deleteEmptyValuesStr =>
                          formFields(Symbol("entities")) { entitiesTSV =>
                            complete {
                              val isAsync = java.lang.Boolean.valueOf(asyncStr) // for lenient parsing
                              val deleteEmptyValues = java.lang.Boolean.valueOf(deleteEmptyValuesStr) // for lenient parsing
                              entityServiceConstructor(FlexibleModelSchema).importEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV, userInfo, isAsync, deleteEmptyValues)
                            }
                          }
                        }
                      }
                    }
                  }
                } ~
                path("importEntities") {
                  post {
                    requireUserInfo() { userInfo =>
                      parameter("deleteEmptyValues" ? "false") { deleteEmptyValuesStr =>
                        formFields(Symbol("entities")) { entitiesTSV =>
                          complete {
                            val deleteEmptyValues = java.lang.Boolean.valueOf(deleteEmptyValuesStr) // for lenient parsing
                            entityServiceConstructor(FirecloudModelSchema).importEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV, userInfo, deleteEmptyValues = deleteEmptyValues)
                          }
                        }
                      }
                    }
                  }
                } ~
                path("importBagit"){
                  post {
                    requireUserInfo() { userInfo =>
                      entity(as[BagitImportRequest]) { bagitRq =>
                        complete { entityServiceConstructor(FirecloudModelSchema).importBagit(workspaceNamespace, workspaceName, bagitRq, userInfo) }
                      }
                    }
                  }
                } ~
                // POST importPFB will likely be deprecated in the future; use POST importJob instead
                path("importPFB") {
                  post {
                    requireUserInfo() { userInfo =>
                      // this endpoint does not accept a filetype. We hardcode the filetype to "pfb".
                      entity(as[PFBImportRequest]) { pfbRequest =>
                        val importRequest = AsyncImportRequest(pfbRequest.url, FILETYPE_PFB, None)
                        complete { entityServiceConstructor(FlexibleModelSchema).importJob(workspaceNamespace, workspaceName, importRequest, userInfo) }
                      }
                    }
                  }
                } ~
                path("importJob") {
                  post {
                    requireUserInfo() { userInfo =>
                      entity(as[AsyncImportRequest]) { importRequest =>
                        complete { entityServiceConstructor(FlexibleModelSchema).importJob(workspaceNamespace, workspaceName, importRequest, userInfo) }
                      }
                    }
                  }
                } ~
                // GET importPFB is deprecated; use GET importJob instead
                path(("importPFB" | "importJob")) {
                  get {
                    requireUserInfo() { _ =>
                      extract(_.request.uri.query()) { query =>
                        passthrough(Uri(encodeUri(s"${FireCloudConfig.ImportService.server}/$workspaceNamespace/$workspaceName/imports")).withQuery(query), HttpMethods.GET)
                      }
                    }
                  }
                } ~
                // GET importPFB/jobId is deprecated; use GET importJob/jobId instead
                path(("importPFB" | "importJob") / Segment) { jobId =>
                  get {
                    requireUserInfo() { userInfo =>
                      passthrough(Uri(encodeUri(s"${FireCloudConfig.ImportService.server}/$workspaceNamespace/$workspaceName/imports/$jobId")), HttpMethods.GET)
                    }
                  }
                } ~
                path("updateAttributes") {
                  patch {
                    requireUserInfo() { userInfo: UserInfo =>
                      entity(as[Seq[AttributeUpdateOperation]]) { replacementAttributes =>
                        complete { workspaceServiceConstructor(userInfo).updateWorkspaceAttributes(workspaceNamespace, workspaceName, replacementAttributes) }
                      }
                    }
                  }
                } ~
                path("setAttributes") {
                  patch {
                    requireUserInfo() { userInfo =>
                      implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
                      entity(as[AttributeMap]) { newAttributes =>
                        complete { workspaceServiceConstructor(userInfo).setWorkspaceAttributes(workspaceNamespace, workspaceName, newAttributes) }
                      }
                    }
                  }
                } ~
                path("exportAttributesTSV") {
                  get {
                    requireUserInfo() { userInfo =>
                      complete { workspaceServiceConstructor(userInfo).exportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, workspaceName + filename) }
                    }
                  }
                } ~
                path("importAttributesTSV") {
                  post {
                    requireUserInfo() { userInfo =>
                      formFields(Symbol("attributes")) { attributesTSV =>
                        complete { workspaceServiceConstructor(userInfo).importAttributesFromTSV(workspaceNamespace, workspaceName, attributesTSV) }
                      }
                    }
                  }
                } ~
                path("acl") {
                  patch {
                    requireUserInfo() { userInfo =>
                      parameter(Symbol("inviteUsersNotFound").?) { inviteUsersNotFound =>
                        entity(as[List[WorkspaceACLUpdate]]) { aclUpdates =>
                          complete { workspaceServiceConstructor(userInfo).updateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, userInfo.userEmail, userInfo.id, inviteUsersNotFound.getOrElse("false").toBoolean) }
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
                    requireUserInfo() { userInfo =>
                      complete { workspaceServiceConstructor(userInfo).getCatalog(workspaceNamespace, workspaceName, userInfo) }
                    }
                  } ~
                    patch {
                      requireUserInfo() { userInfo =>
                        entity(as[Seq[WorkspaceCatalog]]) { updates =>
                          complete { workspaceServiceConstructor(userInfo).updateCatalog(workspaceNamespace, workspaceName, updates, userInfo) }
                        }
                      }
                    }
                } ~
                path("checkBucketReadAccess") {
                  requireUserInfo() { _ =>
                    passthrough(workspacePath + "/checkBucketReadAccess", HttpMethods.GET)
                  }
                } ~
                path("checkIamActionWithLock" / Segment) { samAction =>
                  requireUserInfo() { _ =>
                    passthrough(workspacePath + "/checkIamActionWithLock/" + samAction, HttpMethods.GET)
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
                    requireUserInfo() { userInfo =>
                      entity(as[WorkspaceRequest]) { createRequest =>
                        // the only reason this is not a passthrough is because library needs to overwrite any publish and discoverableByGroups values
                        val cloneRequest = createRequest.copy(attributes = createRequest.attributes + (AttributeName("library","published") -> AttributeBoolean(false)) + (AttributeName("library","discoverableByGroups") -> AttributeValueEmptyList))
                        complete { workspaceServiceConstructor(userInfo).cloneWorkspace(workspaceNamespace, workspaceName, cloneRequest) }
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
                    requireUserInfo() { userInfo =>
                      complete { workspaceServiceConstructor(userInfo).getStorageCostEstimate(workspaceNamespace, workspaceName) }
                    }
                  }
                } ~
                path("tags") {
                  requireUserInfo() { userInfo =>
                    get {
                      complete { workspaceServiceConstructor(userInfo).getTags(workspaceNamespace, workspaceName) }
                    } ~
                      put {
                        entity(as[List[String]]) { tags =>
                          complete { workspaceServiceConstructor(userInfo).putTags(workspaceNamespace, workspaceName, tags) }
                        }
                      } ~
                      patch {
                        entity(as[List[String]]) { tags =>
                          complete { workspaceServiceConstructor(userInfo).patchTags(workspaceNamespace, workspaceName, tags) }
                        }
                      } ~
                      delete {
                        entity(as[List[String]]) { tags =>
                          complete { workspaceServiceConstructor(userInfo).deleteTags(workspaceNamespace, workspaceName, tags) }
                        }
                      }
                  }
                } ~
                path("permissionReport") {
                  requireUserInfo() { userInfo =>
                    post {
                      entity(as[PermissionReportRequest]) { reportInput =>
                        complete { permissionReportServiceConstructor(userInfo).getPermissionReport(workspaceNamespace, workspaceName, reportInput) }
                      }
                    }
                  }
                }
            }
        }
      }
}
