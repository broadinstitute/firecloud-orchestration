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

  val cromIamApiServiceRoutes: Route = {
    val localBase = s"/api/workflows/v1"
    pathPrefix("workflows" / Segment / Segment / "backend" / "metadata" / Segments) {(version, workflowId, operationSegments) =>
      val suffix = operationSegments.mkString("/")
      passthroughImpl(Uri.Path(localBase), Uri(rawlsWorkflowRoot), Option(s"/${workflowId}/genomics/${suffix}"))
    } ~
    pathPrefix( "workflows" / Segments ) { segmentsArr =>
      streamingPassthrough(Uri.Path(localBase) -> Uri(workflowRoot))
    } ~
    pathPrefix( "womtool" / Segment ) { _ =>
      path( "describe" ) {
        pathEnd {
          post {
            passthrough(s"$womtoolRoute/describe", HttpMethods.POST)
          }
        }
      }
    }
  }

  val cromIamEngineRoutes: Route =
    pathPrefix( "engine" / Segment ) { _ =>
      streamingPassthrough(Uri.Path("/engine/v1") -> Uri(engineRoot))
    }
}
