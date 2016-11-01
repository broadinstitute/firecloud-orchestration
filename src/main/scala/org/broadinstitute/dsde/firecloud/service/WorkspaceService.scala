package org.broadinstitute.dsde.firecloud.service

import akka.actor._
import akka.pattern._
import akka.event.Logging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
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
  case class UpdateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) extends WorkspaceServiceMessage
  case class UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) extends WorkspaceServiceMessage

  def props(workspaceServiceConstructor: UserInfo => WorkspaceService, userInfo: UserInfo): Props = {
    Props(workspaceServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new WorkspaceService(userInfo, app.rawlsDAO, app.thurloeDAO)
}

class WorkspaceService(protected val argUserInfo: UserInfo, val rawlsDAO: RawlsDAO, val thurloeDAO: ThurloeDAO) extends Actor with WorkspaceServiceSupport {

  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)

  implicit val userInfo = argUserInfo

  import WorkspaceService._

  override def receive: Receive = {

    case UpdateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) =>
      updateWorkspaceAttributes(workspaceNamespace, workspaceName, newAttributes) pipeTo sender
    case UpdateWorkspaceACL(workspaceNamespace: String, workspaceName: String, aclUpdates: Seq[WorkspaceACLUpdate], originEmail: String) =>
      updateWorkspaceACL(workspaceNamespace, workspaceName, aclUpdates, originEmail) pipeTo sender

  }

  def updateWorkspaceAttributes(workspaceNamespace: String, workspaceName: String, newAttributes: AttributeMap) = {
    rawlsDAO.getWorkspace(workspaceNamespace, workspaceName) flatMap { workspaceResponse =>
      // this is technically vulnerable to a race condition in which the workspace attributes have changed
      // between the time we retrieved them and here, where we update them.
      val allOperations = generateAttributeOperations(workspaceResponse.workspace.get.attributes, newAttributes)
      rawlsDAO.patchWorkspaceAttributes(workspaceNamespace, workspaceName, allOperations) map (RequestComplete(_))
    }
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
