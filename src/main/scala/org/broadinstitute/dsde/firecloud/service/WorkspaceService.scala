package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.WorkspaceService.UpdateWorkspaceACL
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by mbemis on 10/19/16.
 */
object WorkspaceService {
  sealed trait WorkspaceServiceMessage
  case class UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) extends WorkspaceServiceMessage

  def props(workspaceServiceConstructor: UserInfo => WorkspaceService, userInfo: UserInfo): Props = {
    Props(workspaceServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userInfo, app.rawlsDAO, app.thurloeDAO)
}

class WorkspaceService(protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO, val thurloeDAO: ThurloeDAO) extends Actor {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  implicit val userInfo = argUserInfo

  override def receive: Receive = {

    case UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) =>
      updateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, originEmail) pipeTo sender

  }

  def updateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) = {

    val aclUpdate = rawlsDAO.patchWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates)
    aclUpdate map { actualUpdates =>

      val allNotifications = actualUpdates.map {
        case removed if removed.accessLevel.equals(WorkspaceAccessLevels.NoAccess) => WorkspaceRemovedNotification(removed.email, removed.accessLevel.toString, workspaceNamespace, workspaceName, originEmail)
        case added => WorkspaceAddedNotification(added.email, added.accessLevel.toString, workspaceNamespace, workspaceName, originEmail)
      }

      thurloeDAO.sendNotifications(allNotifications)

      RequestComplete(actualUpdates)
    }
  }

}
