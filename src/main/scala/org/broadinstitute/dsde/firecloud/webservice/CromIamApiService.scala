package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import akka.http.scaladsl.model.{HttpMethods, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._

trait CromIamApiService extends FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  lazy val workflowRoot: String = FireCloudConfig.CromIAM.authUrl + "/workflows/v1"
  lazy val womtoolRoute: String = FireCloudConfig.CromIAM.authUrl + "/womtool/v1"
  lazy val engineRoot: String = FireCloudConfig.CromIAM.baseUrl + "/engine/v1"
  lazy val rawlsWorkflowRoot: String = FireCloudConfig.Rawls.authUrl + "/workflows"

  // This is the subset of CromIAM endpoints required for Job Manager. Orchestration is acting as a proxy between
  // CromIAM and Job Manager as of February 2019.
  // Adam Nichols, 2019-02-04

  val cromIamApiServiceRoutes: Route =
    pathPrefix( "workflows" / Segment ) { _ =>
      path("query") {
        pathEnd {
          get {
            extract(_.request.uri.query()) { query =>
              passthrough(Uri(s"$workflowRoot/query").withQuery(query), HttpMethods.GET)
            }
          } ~
          post {
            passthrough(s"$workflowRoot/query", HttpMethods.POST)
          }
        }
      } ~
      path("callcaching" / "diff") {
        pathEnd {
          get {
            extract(_.request.uri.query()) { query =>
              passthrough(Uri(s"$workflowRoot/callcaching/diff").withQuery(query), HttpMethods.GET)
            }
          }
        }
      } ~
      pathPrefix( Segment ) { workflowId: String =>
        path("abort") {
          pathEnd {
            post {
              passthrough(s"$workflowRoot/$workflowId/abort", HttpMethods.POST)
            }
          }
        } ~
        path("metadata") {
          pathEnd {
            get {
              extract(_.request.uri.query()) { query =>
                passthrough(Uri(s"$workflowRoot/$workflowId/metadata").withQuery(query), HttpMethods.GET)
              }
            }
          }
        } ~
        path("labels") {
          pathEnd {
            patch {
              passthrough(s"$workflowRoot/$workflowId/labels", HttpMethods.PATCH)
            }
          }
        } ~
        path("backend" / "metadata" / Segment.repeat(0,1024, Slash)) { operationId =>
          get {
            passthrough(s"$rawlsWorkflowRoot/$workflowId/genomics/${operationId.mkString("/")}", HttpMethods.GET)
          }
        } ~
        path("releaseHold") {
          pathEnd {
            post {
              passthrough(s"$workflowRoot/$workflowId/releaseHold", HttpMethods.POST)
            }
          }
        }
      }
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

  val cromIamEngineRoutes: Route =
    pathPrefix( "engine" / Segment ) { _ =>
      path("version" ) {
        pathEnd {
          passthrough(s"$engineRoot/version", HttpMethods.GET)
        }
      } ~
      path("status" ) {
        pathEnd {
          passthrough(s"$engineRoot/status", HttpMethods.GET)
        }
      }
    }

}
