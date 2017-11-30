package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.Trial.TrialOperations
import org.broadinstitute.dsde.firecloud.model.Trial.TrialOperations._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.TrialService.TrialServiceMessage
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
                updateUsers(userInfo, TrialOperations.withName(operation), users))
          }
        }
      }
    }
  }

  private def updateUsers(userInfo: UserInfo,
                          operation: TrialOperation,
                          users: Seq[String]): TrialServiceMessage = operation match {
    case Enable => TrialService.EnableUsers(userInfo, users)
    case Disable => TrialService.DisableUsers(userInfo, users)
    case Terminate => TrialService.TerminateUsers(userInfo)
  }

  // TODO: Update for the other valid operations (i.e. disableUser and terminateUser)
  private def isValid(operation: String): Boolean = operation == "enableUser"
}
