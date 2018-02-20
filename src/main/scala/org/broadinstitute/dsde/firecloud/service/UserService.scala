package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impUserImportPermission
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses
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
    // start two requests, in parallel, to fire off workspace list and billing project list
    val billingProjects = rawlsDAO.getProjects
    val workspaces = rawlsDAO.getWorkspaces

    // for-comprehension to extract from the two vals
    val importPerm = for {
      hasProject <- billingProjects.map (_.exists(_.creationStatus == CreationStatuses.Ready))
      hasWorkspace <- workspaces.map { ws => ws.exists(_.accessLevel.compare(WorkspaceAccessLevels.Write) >= 0)} // TODO: also need to check canCompute?
    } yield UserImportPermission(
      billingProject = hasProject,
      writableWorkspace = hasWorkspace)

    // TODO: do we need recover/error handling here?

    // respond with a UserImportPermission
    // TODO: if all values are false, should we still return a 200?
    importPerm map { x=> RequestComplete(StatusCodes.OK, x)}
  }

}
