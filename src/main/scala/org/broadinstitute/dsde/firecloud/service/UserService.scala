package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsBillingProjectMembership
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impUserImportPermission
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses
import org.broadinstitute.dsde.firecloud.model.{UserImportPermission, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.UserService.{BillingProjectMembership, ImportPermission}
import org.broadinstitute.dsde.rawls.model.{RawlsBillingProjectName, WorkspaceAccessLevels}
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport

import scala.concurrent.{ExecutionContext, Future}

object UserService {
  sealed trait UserServiceMessage

  case object ImportPermission extends UserServiceMessage
  case class BillingProjectMembership(projectName: String) extends UserServiceMessage

  def props(userService: (WithAccessToken) => UserService, userInfo: WithAccessToken): Props = {
    Props(userService(userInfo))
  }

  def constructor(app: Application)(userInfo: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new UserService(app.rawlsDAO, userInfo)
}

class UserService(rawlsDAO: RawlsDAO, userToken: WithAccessToken)(implicit protected val executionContext: ExecutionContext)
  extends Actor with LazyLogging with SprayJsonSupport {

  override def receive = {
    case ImportPermission => importPermission(userToken) pipeTo sender
    case BillingProjectMembership(projectName: String) => getBillingProjectMembership(userToken, projectName) pipeTo sender
  }

  def importPermission(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    // start two requests, in parallel, to fire off workspace list and billing project list
    val billingProjects = rawlsDAO.getProjects
    val workspaces = rawlsDAO.getWorkspaces

    // for-comprehension to extract from the two vals
    // we intentionally only check write access on the workspace (not canCompute); write access without
    // canCompute is an edge case that PO does not feel is worth the effort. The workspace list does not return
    // canCompute, so the effort is somewhat high.
    for {
      hasProject <- billingProjects.map (_.exists(_.creationStatus == CreationStatuses.Ready))
      hasWorkspace <- workspaces.map { ws => ws.exists(_.accessLevel.compare(WorkspaceAccessLevels.Write) >= 0)}
    } yield
      RequestComplete(StatusCodes.OK, UserImportPermission(
        billingProject = hasProject,
        writableWorkspace = hasWorkspace))
  }

  def getBillingProjectMembership(implicit userToken: WithAccessToken, projectName: String): Future[PerRequestMessage] = {
    val billingProjectName = RawlsBillingProjectName(projectName)
    val billingProjectMembership = rawlsDAO.getProjects.map(_.find(_.projectName.equals(billingProjectName)))
    billingProjectMembership.map {
      case Some(membership) => RequestComplete(StatusCodes.OK, membership)
      case None => RequestComplete(StatusCodes.NotFound)
    }
  }

}
