package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat

import akka.actor.Props
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{HttpMethods, StatusCodes, Uri}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.{RequestContext, Route}
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
//import spray.http._
//import spray.httpx.unmarshalling._
//import spray.httpx.SprayJsonSupport._
//import spray.routing._

import scala.concurrent.ExecutionContext

trait WorkspaceApiService extends FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  lazy val log: Logger = LoggerFactory.getLogger(getClass)
  lazy val rawlsWorkspacesRoot: String = FireCloudConfig.Rawls.workspacesUrl

  val workspaceServiceConstructor: WithAccessToken => WorkspaceService
  val permissionReportServiceConstructor: UserInfo => PermissionReportService
  val entityClientConstructor: (ModelSchema) => EntityClient //todo: get rid of requestcontext?

  private val filename = "-workspace-attributes.tsv"

  val workspaceRoutes: Route =
    pathPrefix("cookie-authed") {
      path("workspaces" / Segment / Segment / "exportAttributesTSV") {
        (workspaceNamespace, workspaceName) =>
          cookie("FCtoken") { tokenCookie =>
            mapRequest(r => addCredentials(OAuth2BearerToken(tokenCookie.value)).apply(r)) {
              complete { workspaceServiceConstructor(new AccessToken(OAuth2BearerToken(tokenCookie.value))).ExportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, workspaceName + filename) }
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
                  parameter('q.?) { queryString =>
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
                      complete { workspaceServiceConstructor(userInfo).DeleteWorkspace(workspaceNamespace, workspaceName) }
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
                      requireUserInfo() { _ =>
                        entity(as[MethodConfiguration]) { methodConfig => requestContext =>
                          if (!methodConfig.outputs.exists { param => param._2.value.startsWith("this.library:") || param._2.value.startsWith("workspace.library:")})
                            passthrough(workspacePath + "/methodconfigs", HttpMethods.GET, HttpMethods.POST)
                          else
                            requestContext.complete(StatusCodes.Forbidden, ErrorReport("Methods and configurations can not create or modify library attributes"))
                        }
                      }
                    }
                } ~
                path("flexibleImportEntities") {
                  post {
                    requireUserInfo() { _ =>
                      formFields('entities) { entitiesTSV =>
                        //TODO: respondWithJson here and other places. it's deprecated so decide new solution
                        complete { entityClientConstructor(FlexibleModelSchema).ImportEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV) }
                      }
                    }
                  }
                } ~
                path("importEntities") {
                  post {
                    requireUserInfo() { _ =>
                      formFields('entities) { entitiesTSV =>
                        //TODO: respondWithJson
                        complete { entityClientConstructor(FirecloudModelSchema).ImportEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV) }
                      }
                    }
                  }
                } ~
                path("importBagit"){
                  post {
                    requireUserInfo() { userInfo =>
                      entity(as[BagitImportRequest]) { bagitRq =>
                        complete { entityCleitnConstructor(FirecloudModelSchema).ImportBagit(workspaceNamespace, workspaceName, bagitRq) }
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
                  } ~
                    get {
                      requireUserInfo() { _ =>
                        extract(_.request.uri.query()) { query =>
                          passthrough(Uri(encodeUri(s"${FireCloudConfig.ImportService.server}/$workspaceNamespace/$workspaceName/imports")).withQuery(query()), HttpMethods.GET)
                        }
                      }
                    }
                } ~
                path("importPFB" / Segment) { jobId =>
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
                        complete { workspaceServiceConstructor(userInfo).UpdateWorkspaceAttributes(workspaceNamespace, workspaceName, replacementAttributes) }
                      }
                    }
                  }
                } ~
                path("setAttributes") {
                  patch {
                    requireUserInfo() { userInfo =>
                      implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
                      entity(as[AttributeMap]) { newAttributes =>
                        complete { workspaceServiceConstructor(userInfo).SetWorkspaceAttributes(workspaceNamespace, workspaceName, newAttributes) }
                      }
                    }
                  }
                } ~
                path("exportAttributesTSV") {
                  get {
                    requireUserInfo() { userInfo =>
                      complete { workspaceServiceConstructor(userInfo).ExportWorkspaceAttributesTSV(workspaceNamespace, workspaceName, workspaceName + filename) }
                    }
                  }
                } ~
                path("importAttributesTSV") {
                  post {
                    requireUserInfo() { userInfo =>
                      formFields('attributes) { attributesTSV =>
                        respondWithJSON {
                          complete { workspaceServiceConstructor(userInfo).ImportAttributesFromTSV(workspaceNamespace, workspaceName, attributesTSV) }
                        }
                      }
                    }
                  }
                } ~
                path("acl") {
                  patch {
                    requireUserInfo() { userInfo =>
                      parameter('inviteUsersNotFound.?) { inviteUsersNotFound =>
                        entity(as[List[WorkspaceACLUpdate]]) { aclUpdates =>
                          complete { workspaceServiceConstructor(userInfo).UpdateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, userInfo.userEmail, userInfo.id, inviteUsersNotFound.getOrElse("false").toBoolean) }
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
                      complete { workspaceServiceConstructor(userInfo).GetCatalog(workspaceNamespace, workspaceName, userInfo) }
                    }
                  } ~
                    patch {
                      requireUserInfo() { userInfo =>
                        entity(as[Seq[WorkspaceCatalog]]) { updates =>
                          complete { workspaceServiceConstructor(userInfo).UpdateCatalog(workspaceNamespace, workspaceName, updates, userInfo) }
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
                    requireUserInfo() { userInfo =>
                      complete { workspaceServiceConstructor(userInfo).GetStorageCostEstimate(workspaceNamespace, workspaceName) }
                    }
                  }
                } ~
                path("tags") {
                  requireUserInfo() { userInfo =>
                    get {
                      complete { workspaceServiceConstructor(userInfo).GetTags(workspaceNamespace, workspaceName) }
                    } ~
                      put {
                        entity(as[List[String]]) { tags =>
                          complete { workspaceServiceConstructor(userInfo).PutTags(workspaceNamespace, workspaceName, tags) }
                        }
                      } ~
                      patch {
                        entity(as[List[String]]) { tags =>
                          complete { workspaceServiceConstructor(userInfo).PatchTags(workspaceNamespace, workspaceName, tags) }
                        }
                      } ~
                      delete {
                        entity(as[List[String]]) { tags =>
                          complete { workspaceServiceConstructor(userInfo).DeleteTags(workspaceNamespace, workspaceName, tags) }
                        }
                      }
                  }
                } ~
                path("permissionReport") {
                  requireUserInfo() { userInfo =>
                    post {
                      entity(as[PermissionReportRequest]) { reportInput =>
                        complete { permissionReportServiceConstructor(userInfo).GetPermissionReport(workspaceNamespace, workspaceName, reportInput) }
                      }
                    }
                  }
                }
            }
        }
      }
}
