package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.Route

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectives
import org.broadinstitute.dsde.firecloud.utils.StreamingPassthrough

trait WorkspaceV2ApiService extends FireCloudDirectives with StreamingPassthrough {

  private val rawlsWorkspacesV2Root: String = FireCloudConfig.Rawls.workspacesV2Url

  val workspaceV2Routes: Route =
    pathPrefix("api" / "workspaces" / "v2") {
      streamingPassthrough(rawlsWorkspacesV2Root)
    }
}
