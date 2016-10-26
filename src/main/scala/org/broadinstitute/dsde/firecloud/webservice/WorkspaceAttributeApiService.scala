package org.broadinstitute.dsde.firecloud.webservice

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service._
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.slf4j.LoggerFactory
import spray.http.{HttpEntity, HttpMethods}
import spray.routing._

import scala.concurrent.ExecutionContext


trait WorkspaceAttributeApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives
  with StandardUserInfoDirectives with LazyLogging {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  val workspaceAttributeServiceConstructor: UserInfo => WorkspaceAttributeService

  val workspaceAttributeRoutes: Route =
    pathPrefix("api" / "workspaces") {
      requireUserInfo() { userInfo =>
        pathPrefix(Segment / Segment) { (workspaceNamespace, workspaceName) =>
          path("exportAttributes") {
            val filename = workspaceName + "-WORKSPACE-ATTRIBUTES.txt"
            get { requestContext =>
              perRequest(requestContext,
                WorkspaceAttributeService.props(workspaceAttributeServiceConstructor, userInfo),
                WorkspaceAttributeService.ExportWorkspaceAttributes(FireCloudConfig.Rawls.exportWorkspaceAttributes(workspaceNamespace, workspaceName), workspaceNamespace, workspaceName, filename))
            }
          } ~
          path("updateAttributes") {
            val rawlsPath = FireCloudConfig.Rawls.workspacesUrl + "/%s/%s"
            passthrough(rawlsPath.format(workspaceNamespace, workspaceName), HttpMethods.PATCH)
          } ~
          path("importAttributes") {
            post {
              formFields('attributes) { attributesTSV =>
                respondWithJSON {requestContext =>
                  perRequest(requestContext,
                    WorkspaceAttributeService.props(workspaceAttributeServiceConstructor, userInfo),
                    WorkspaceAttributeService.ImportAttributesFromTSV(workspaceNamespace, workspaceName, attributesTSV)
                  )
                }
              }
            }
          }
        }
      }
    }

}
