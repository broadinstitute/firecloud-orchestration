package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, TrialService, UserService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.HttpMethods
import spray.routing.{HttpService, Route}
import spray.routing.HttpService

trait CromIamApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  val cromIamApiServiceRoutes: Route =
    pathPrefix( "workflows" / Segment ) { apiVersion: String =>
      path("query") {
        pathEnd {
          get {
            passthrough(s"https://cromwell.caas-dev.broadinstitute.org/api/workflows/$apiVersion/query", HttpMethods.GET)
          } ~
          post {
            passthrough(s"https://cromwell.caas-dev.broadinstitute.org/api/workflows/$apiVersion/query", HttpMethods.POST)
          }
        }
      } ~
      pathPrefix( Segment ) { workflowId: String =>
        path("abort") {
          pathEnd {
            post {
              passthrough(s"https://cromwell.caas-dev.broadinstitute.org/api/workflows/$apiVersion/$workflowId/abort", HttpMethods.POST)
            }
          }
        } ~
        path("metadata") {
          pathEnd {
            get {
              passthrough(s"https://cromwell.caas-dev.broadinstitute.org/api/workflows/$apiVersion/$workflowId/metadata", HttpMethods.GET)
            }
          }
        } ~
        path("labels") {
          pathEnd {
            patch {
              passthrough(s"https://cromwell.caas-dev.broadinstitute.org/api/workflows/$apiVersion/$workflowId/labels", HttpMethods.PATCH)
            }
          }
        }
      }
    }

  val cromIamEngineRoutes: Route =
    pathPrefix( "engine" / Segment ) { apiVersion: String =>
      path("version" ) {
        pathEnd {
          passthrough(s"https://cromwell.caas-dev.broadinstitute.org/engine/$apiVersion/version", HttpMethods.GET)
        }
      } ~
      path("status" ) {
        pathEnd {
          passthrough(s"https://cromwell.caas-dev.broadinstitute.org/engine/$apiVersion/status", HttpMethods.GET)
        }
      }
    }

}
