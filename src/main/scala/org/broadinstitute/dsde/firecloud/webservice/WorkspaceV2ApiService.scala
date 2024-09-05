package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.Route

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.{StandardUserInfoDirectives, StreamingPassthrough}

import scala.concurrent.ExecutionContext

trait WorkspaceV2ApiService
    extends FireCloudRequestBuilding
    with FireCloudDirectives
    with StandardUserInfoDirectives
    with StreamingPassthrough {

  implicit val executionContext: ExecutionContext

  lazy val rawlsWorkspacesV2Root: String = FireCloudConfig.Rawls.workspacesV2Url

  val workspaceV2Routes: Route =
    pathPrefix("api" / "workspaces" / "v2") {
      pathPrefix(Segment / Segment) {
        (workspaceNamespace, workspaceName) => {
          val workspaceV2Path = encodeUri(rawlsWorkspacesV2Root + "/%s/%s".format(workspaceNamespace, workspaceName))
          path("settings") {
            requireUserInfo() { _ =>
              streamingPassthrough(workspaceV2Path + "/settings")
            }
          }
        }
      }
    }
}
