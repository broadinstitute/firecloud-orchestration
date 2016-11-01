package org.broadinstitute.dsde.firecloud.webservice

import java.text.SimpleDateFormat

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.service.{WorkspaceService, FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.firecloud.{EntityClient, FireCloudConfig}
import org.slf4j.LoggerFactory
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.http.{HttpMethods, HttpEntity}
import spray.httpx.unmarshalling._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import scala.concurrent.ExecutionContext

trait WorkspaceApiService extends HttpService with FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  private final val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  lazy val log = LoggerFactory.getLogger(getClass)
  lazy val rawlsWorkspacesRoot = FireCloudConfig.Rawls.workspacesUrl

  val workspaceServiceConstructor: UserInfo => WorkspaceService

  def transformSingleWorkspaceRequest(entity: HttpEntity): HttpEntity = {
    entity.as[RawlsWorkspaceResponse] match {
      case Right(rwr) => new UIWorkspaceResponse(rwr).toJson.prettyPrint
      case Left(error) =>
        log.error("Unable to unmarshal entity -- " + error.toString)
        entity
    }
  }

  def transformListWorkspaceRequest(entity: HttpEntity): HttpEntity = {
    entity.as[List[RawlsWorkspaceResponse]] match {
      case Right(lrwr) => lrwr.map(new UIWorkspaceResponse(_)).toJson.prettyPrint
      case Left(error) =>
        log.error("Unable to unmarshal entity -- " + error.toString)
        entity
    }
  }

  val workspaceRoutes: Route =
    requireUserInfo() { userInfo =>
      pathPrefix("api") {
        pathPrefix("workspaces") {
          pathEnd {
            mapHttpResponseEntity(transformListWorkspaceRequest) {
              passthrough(rawlsWorkspacesRoot, HttpMethods.GET)
            } ~
              post {
                entity(as[WorkspaceCreate]) { createRequest => requestContext =>
                  val extReq = Post(FireCloudConfig.Rawls.workspacesUrl, new RawlsWorkspaceCreate(createRequest))
                  externalHttpPerRequest(requestContext, extReq)
                }
              }
          } ~
            pathPrefix(Segment / Segment) { (workspaceNamespace, workspaceName) =>
              val workspacePath = rawlsWorkspacesRoot + "/%s/%s".format(workspaceNamespace, workspaceName)
              pathEnd {
                mapHttpResponseEntity(transformSingleWorkspaceRequest) {
                  passthrough(workspacePath, HttpMethods.GET)
                } ~
                  passthrough(workspacePath, HttpMethods.DELETE)
              } ~
                path("methodconfigs") {
                  passthrough(workspacePath + "/methodconfigs", HttpMethods.GET, HttpMethods.POST)
                } ~
                path("importEntities") {
                  post {
                    formFields('entities) { entitiesTSV =>
                      respondWithJSON { requestContext =>
                        perRequest(requestContext, Props(new EntityClient(requestContext)),
                          EntityClient.ImportEntitiesFromTSV(workspaceNamespace, workspaceName, entitiesTSV))
                      }
                    }
                  }
                } ~
                path("exportAttributes") {
                  val filename = workspaceName + "-WORKSPACE-ATTRIBUTES.txt"
                  get { requestContext =>
                      perRequest(requestContext,
                          WorkspaceService.props(workspaceServiceConstructor, userInfo),
                          WorkspaceService.ExportWorkspaceAttributes(FireCloudConfig.Rawls.exportWorkspaceAttributes(workspaceNamespace, workspaceName), workspaceNamespace, workspaceName, filename))
                    }
                } ~
                path("importAttributes") {
                  post {
                    formFields('attributes) { attributesTSV =>
                      respondWithJSON {requestContext =>
                        perRequest(requestContext,
                          WorkspaceService.props(workspaceServiceConstructor, userInfo),
                          WorkspaceService.ImportAttributesFromTSV(workspaceNamespace, workspaceName, attributesTSV)
                          )
                      }
                    }
                  }
                }
                path("updateAttributes") {
                  passthrough(workspacePath, HttpMethods.PATCH)
                } ~
                path("acl") {
                  patch {
                    entity(as[List[WorkspaceACLUpdate]]) { aclUpdates => requestContext =>
                      perRequest(requestContext,
                        WorkspaceService.props(workspaceServiceConstructor, userInfo),
                        WorkspaceService.UpdateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, userInfo.userEmail))
                    }
                  } ~
                    get {
                      passthrough(workspacePath + "/acl", HttpMethods.GET)
                    }
                } ~
                path("checkBucketReadAccess") {
                  passthrough(workspacePath + "/checkBucketReadAccess", HttpMethods.GET)
                } ~
                path("clone") {
                  post {
                    entity(as[WorkspaceCreate]) { createRequest => requestContext =>
                      val extReq = Post(workspacePath + "/clone", new RawlsWorkspaceCreate(createRequest))
                      externalHttpPerRequest(requestContext, extReq)
                    }
                  }
                } ~
                path("lock") {
                  passthrough(workspacePath + "/lock", HttpMethods.PUT)
                } ~
                path("unlock") {
                  passthrough(workspacePath + "/unlock", HttpMethods.PUT)
                }
            }
        }
      }
    }
}
