package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.broadinstitute.dsde.firecloud.FireCloudConfig

import scala.concurrent.ExecutionContext

class WorkspaceV2ApiServiceSpec extends BaseServiceSpec with WorkspaceV2ApiService {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  private val testableRoutes = workspaceV2Routes

  "WorkspaceV2 passthrough" - {

    "should pass through HTTP GET for settings" in {
      val settingsEndpoint = FireCloudConfig.Rawls.workspacesV2Url + "/namespace/name/settings"
      checkIfPassedThrough(testableRoutes, HttpMethods.GET, settingsEndpoint, toBeHandled = true)
    }
  }
}
