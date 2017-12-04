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
      path("trial" / "manager" / "enable|disable|terminate".r) { (operation: String) =>
        requireUserInfo() { managerInfo => // We will need the manager's credentials for authentication downstream
          entity(as[Seq[String]]) { userEmails => requestContext =>
              perRequest(requestContext,
                TrialService.props(trialServiceConstructor),
                updateUsers(managerInfo, TrialOperations.withName(operation), userEmails))
          }
        }
      }
    }
  }

  private def updateUsers(managerInfo: UserInfo,
                          operation: TrialOperation,
                          userEmails: Seq[String]): TrialServiceMessage = operation match {
    case Enable => TrialService.EnableUsers(managerInfo, userEmails)
    case Disable => TrialService.DisableUsers(managerInfo, userEmails)
    case Terminate => TrialService.TerminateUsers(managerInfo, userEmails)
  }

}
