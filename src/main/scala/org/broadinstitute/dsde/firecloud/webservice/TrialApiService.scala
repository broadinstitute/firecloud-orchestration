package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator, TrialService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._
import spray.routing.{HttpService, Route}

trait TrialApiService extends HttpService with PerRequestCreator with FireCloudDirectives
  with StandardUserInfoDirectives with SprayJsonSupport {

  private implicit val executionContext = actorRefFactory.dispatcher
  val trialServiceConstructor: () => TrialService

  val trialApiServiceRoutes: Route = {
    post {
      path("trial" / "manager" / Segment) { operation =>
        // TODO: Validate operation
        requireUserInfo() { userInfo =>
          entity(as[Seq[String]]) { users => requestContext =>
              perRequest(requestContext,
                TrialService.props(trialServiceConstructor),
                TrialService.EnableUsers(userInfo, operation, users))
          }
        }
      }
    }
  }

  // TODO: Update for the other valid operations (i.e. disableUser and terminateUser)
  private def isValid(operation: String): Boolean = operation == "enableUser"
}
