package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.{StandardUserInfoDirectives, StreamingPassthrough}
import akka.http.scaladsl.model.{HttpMethods, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._

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
    pathPrefix( "workflows" / Segments ) { segmentsArr =>
      // TODO: get feedback on this method
      // There is one endpoint that is meant to be redirected to rawls and not cromIAM ("/backend/metadata/" substring present)
      // Root assignment can be handled here, curious if there's any way to handle req.path modifications here as well
      val suffix = segmentsArr.mkString("/")
      val root = if (suffix.contains("/backend/metadata/")) rawlsWorkflowRoot else workflowRoot
      streamingPassthrough(Uri.Path(s"/api/workflows/v1") -> Uri(root))
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
