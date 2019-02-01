package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, TrialService, UserService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.HttpMethods
import spray.routing.{HttpService, Route}
import spray.routing.HttpService

trait CromIamApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  val cromIamApiServiceRoutes: Route =
    pathPrefix( "workflows" / Segment ) { _ =>
      path( "query" ) {
        pathEnd {
          get {
            passthrough("https://cromwell.caas-dev.broadinstitute.org/api/workflows/v1/query", HttpMethods.GET)
          } ~
          post {
            passthrough("https://cromwell.caas-dev.broadinstitute.org/api/workflows/v1/query", HttpMethods.POST)
          }
        }
      } ~
      path( "abort" ) {
        pathEnd {
          post {
            passthrough("https://cromwell.caas-dev.broadinstitute.org/api/workflows/v1/abort", HttpMethods.POST)
          }
        }
      }
    }

  val cromIamEngineRoutes: Route =
    pathPrefix( "engine" / Segment ) {  _ =>
      path("version" ) {
        pathEnd {
          passthrough("https://cromwell.caas-dev.broadinstitute.org/engine/v1/version", HttpMethods.GET)
        }
      } ~
      path("status" ) {
        pathEnd {
          passthrough("https://cromwell.caas-dev.broadinstitute.org/engine/v1/status", HttpMethods.GET)
        }
      }
    }

}
