package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impUserImportPermission
import org.broadinstitute.dsde.firecloud.model.{UserImportPermission, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.UserService.ImportPermission
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport

import scala.concurrent.{ExecutionContext, Future}

object UserService {
  sealed trait UserServiceMessage

  case object ImportPermission extends UserServiceMessage

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
  }

  def importPermission(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    // start three requests, as vals, to fire off workspace list, billing project list, billing acct list in parallel
    val billingAccounts = Future(Seq())
    // val billingAccounts = rawlsDAO.getAccounts // TODO: this doesn't exist yet!
    val billingProjects = rawlsDAO.getProjects
    val workspaces = rawlsDAO.getWorkspaces

    // for-comprehension to extract from the three vals
    // TODO: is this the right business logic?
    val importPerm = for {
      hasAccount <- billingAccounts.map (_.nonEmpty)
      hasProject <- billingProjects.map (_.nonEmpty)
      hasWorkspace <- workspaces.map { ws => ws.exists(_.accessLevel.compare(WorkspaceAccessLevels.Write) >= 0)}
    } yield UserImportPermission(
      billingAccount = hasAccount,
      billingProject = hasProject,
      writableWorkspace = hasWorkspace)

    // TODO: do we need recover/error handling here?

    // respond with a UserImportPermission
    // TODO: if all values are false, should we still return a 200?
    importPerm map { x=> RequestComplete(StatusCodes.OK, x)}
  }

}
