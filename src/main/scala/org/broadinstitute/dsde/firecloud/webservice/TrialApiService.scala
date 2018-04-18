package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.Trial.TrialOperations
import org.broadinstitute.dsde.firecloud.model.Trial.TrialOperations._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.TrialService.TrialServiceMessage
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, PerRequestCreator, TrialService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import org.broadinstitute.dsde.rawls.model.ErrorReport
import spray.http.StatusCodes.BadRequest
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._
import spray.routing.{HttpService, Route}

import scala.concurrent.ExecutionContextExecutor

trait TrialApiService extends HttpService with PerRequestCreator with FireCloudDirectives
  with StandardUserInfoDirectives with SprayJsonSupport {

  private implicit val executionContext: ExecutionContextExecutor = actorRefFactory.dispatcher
  val trialServiceConstructor: () => TrialService

  val trialApiServiceRoutes: Route = {
    pathPrefix("trial" / "manager") {
      // TODO: See if it makes sense to use DELETE for terminate, and perhaps PUT for disable
      post {
        path("enable|disable|terminate".r) { (operation: String) =>
          requireUserInfo() { managerInfo => // We will need the manager's credentials for authentication downstream
            entity(as[Seq[String]]) { userEmails =>
              requestContext =>
                perRequest(requestContext,
                  TrialService.props(trialServiceConstructor),
                  updateUsers(managerInfo, TrialOperations.withName(operation), userEmails))
            }
          }
        } ~
        path("projects") {
          parameter("count".as[Int] ? 0) { count =>
            parameter("operation") { op =>
              requireUserInfo() { userInfo => requestContext =>
                val message = op.toLowerCase match {
                  case "create" => Some(TrialService.CreateProjects(userInfo, count))
                  case "verify" => Some(TrialService.VerifyProjects(userInfo))
                  case "count" => Some(TrialService.CountProjects(userInfo))
                  case "report" => Some(TrialService.Report(userInfo))
                  case _ => None
                }
                if (message.nonEmpty)
                  perRequest(requestContext, TrialService.props(trialServiceConstructor), message.get)
                else
                  requestContext.complete(BadRequest, ErrorReport(s"invalid operation '$op'"))
              }
            }
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
