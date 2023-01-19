package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.{StandardUserInfoDirectives, StreamingPassthrough}
import akka.http.scaladsl.model.{HttpMethods, Uri}
import akka.http.scaladsl.server.Route

trait CromIamApiService extends FireCloudRequestBuilding
  with FireCloudDirectives with StandardUserInfoDirectives
  with StreamingPassthrough {

  lazy val workflowRoot: String = FireCloudConfig.CromIAM.authUrl + "/workflows/v1"
  lazy val womtoolRoute: String = FireCloudConfig.CromIAM.authUrl + "/womtool/v1"
  lazy val engineRoot: String = FireCloudConfig.CromIAM.baseUrl + "/engine/v1"
  lazy val rawlsWorkflowRoot: String = FireCloudConfig.Rawls.authUrl + "/workflows"

  // This is the subset of CromIAM endpoints required for Job Manager. Orchestration is acting as a proxy between
  // CromIAM and Job Manager as of February 2019.
  // Adam Nichols, 2019-02-04

  val localBase = s"/api/workflows/v1"

  /*
    NOTE: The rawls routes are a bit different from the cromiam routes. While it does carry the same "/api/workflows" base path,
    there are caveats to its redirect that need to be taken into account
      1) The remote authority isn't CromIAM's baseUrl but rather Rawls' baseUrl
      2) The local path is defined as "/workflows/{version}/{id}/backend/metadata/{operation}, but Rawls endpoint is
         defined as /workflows/{id}/genomics/{operation}, meaning that path reconstruction is necessary
    Since there's only one such route, it was simpler to have an explicit route defined for his edge case and have it evaluated
    before the rest of the workflow routes.
  */
  val rawlsServiceRoute: Route = {
    pathPrefix("workflows" / Segment / Segment / "backend" / "metadata" / Segments) { (version, workflowId, operationSegments) =>
      val suffix = operationSegments.mkString("/")
      streamingPassthroughWithPathRedirect(Uri.Path(localBase) -> Uri(rawlsWorkflowRoot), s"/${workflowId}/genomics/${suffix}")
    }
  }

  val cromIamServiceRoutes: Route =
    pathPrefix("workflows" / Segment) { _ =>
      streamingPassthrough(Uri.Path(localBase) -> Uri(workflowRoot))
    }

  val womToolRoute: Route =
    pathPrefix("womtool" / Segment) { _ =>
      path("describe") {
        pathEnd {
          post {
            passthrough(s"$womtoolRoute/describe", HttpMethods.POST)
          }
        }
      }
    }


  val cromIamApiServiceRoutes = rawlsServiceRoute ~ cromIamServiceRoutes ~ womToolRoute

  val cromIamEngineRoutes: Route = {
    pathPrefix( "engine" / Segment ) { _ =>
      streamingPassthrough(Uri.Path("/engine/v1") -> Uri(engineRoot))
    }
  }


}
