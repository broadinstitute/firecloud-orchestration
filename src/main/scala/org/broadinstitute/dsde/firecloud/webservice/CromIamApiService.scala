package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, TrialService, UserService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.HttpMethods
import spray.routing.{HttpService, Route}
import spray.routing.HttpService

trait CromIamApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives with StandardUserInfoDirectives {

  val cromIamApiServiceRoutes: Route = pathPrefix( "cromiam" ) {
    pathPrefix( "workflows" / Segment ) { _ =>
      path( "query" ) {
        pathEnd {
          get {
            passthrough("https://cromwell.caas-prod.broadinstitute.org/api/workflows/v1/query", HttpMethods.GET)
          } ~
          post {
            passthrough("https://cromwell.caas-prod.broadinstitute.org/api/workflows/v1/query", HttpMethods.POST)
          }
        }
      }
    }
  }


}
