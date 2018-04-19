package org.broadinstitute.dsde.firecloud.service

/**
  * Created by mbemis on 3/26/18.
  */

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.SamDAO
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.{FireCloudManagedGroup, ManagedGroupRoles, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.ManagedGroupService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object ManagedGroupService {
  sealed trait ManagedGroupServiceMessage

  case class CreateGroup(groupName: WorkbenchGroupName) extends ManagedGroupServiceMessage
  case class DeleteGroup(groupName: WorkbenchGroupName) extends ManagedGroupServiceMessage
  case object ListGroups extends ManagedGroupServiceMessage
  case class ListGroupMembers(groupName: WorkbenchGroupName) extends ManagedGroupServiceMessage
  case class OverwriteGroupMembers(groupName: WorkbenchGroupName, role: ManagedGroupRole, membersList: List[WorkbenchEmail]) extends ManagedGroupServiceMessage
  case class AddGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail) extends ManagedGroupServiceMessage
  case class RemoveGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail) extends ManagedGroupServiceMessage
  case class RequestGroupAccess(groupName: WorkbenchGroupName) extends ManagedGroupServiceMessage

  def props(managedGroupService: (WithAccessToken) => ManagedGroupService, userInfo: WithAccessToken): Props = {
    Props(managedGroupService(userInfo))
  }

  def constructor(app: Application)(userInfo: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new ManagedGroupService(app.samDAO, userInfo)
}

class ManagedGroupService(samDAO: SamDAO, userToken: WithAccessToken)(implicit protected val executionContext: ExecutionContext)
  extends Actor with LazyLogging with SprayJsonSupport {

  override def receive = {
    case CreateGroup(groupName) => createGroup(groupName)(userToken) pipeTo sender
    case DeleteGroup(groupName) => deleteGroup(groupName)(userToken) pipeTo sender
    case ListGroups => listGroups(userToken) pipeTo sender
    case ListGroupMembers(groupName) => listGroupMembers(groupName)(userToken) pipeTo sender
    case AddGroupMember(groupName, role, email) => addGroupMember(groupName, role, email)(userToken) pipeTo sender
    case RemoveGroupMember(groupName, role, email) => removeGroupMember(groupName, role, email)(userToken) pipeTo sender
    case OverwriteGroupMembers(groupName, role, membersList) => overwriteGroupMembers(groupName, role, membersList)(userToken) pipeTo sender
    case RequestGroupAccess(groupName) => requestGroupAccess(groupName)(userToken) pipeTo sender
  }

  def createGroup(groupName: WorkbenchGroupName)(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    val membersList = for {
      _ <- samDAO.createGroup(groupName)
      listMembers <- listGroupMembersInternal(groupName)
      _ <- samDAO.addPolicyMember("managed-group", groupName.value, "admin-notifier", WorkbenchEmail("GROUP_All_Users@dev.test.firecloud.org")) //todo
    } yield listMembers

    membersList.map(response => RequestComplete(StatusCodes.Created, response))
  }

  def deleteGroup(groupName: WorkbenchGroupName)(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    samDAO.deleteGroup(groupName).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def listGroups(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    samDAO.listGroups.map(response => RequestComplete(StatusCodes.OK, response))
  }

  def listGroupMembers(groupName: WorkbenchGroupName)(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    listGroupMembersInternal(groupName).map(response => RequestComplete(StatusCodes.OK, response))
  }

  def addGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    samDAO.addGroupMember(groupName, role, email).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def removeGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    samDAO.removeGroupMember(groupName, role, email).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def overwriteGroupMembers(groupName: WorkbenchGroupName, role: ManagedGroupRole, membersList: List[WorkbenchEmail])(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    samDAO.overwriteGroupMembers(groupName, role, membersList).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def requestGroupAccess(groupName: WorkbenchGroupName)(implicit userToken: WithAccessToken): Future[PerRequestMessage] = {
    samDAO.requestGroupAccess(groupName).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  private def listGroupMembersInternal(groupName: WorkbenchGroupName)(implicit userToken: WithAccessToken): Future[FireCloudManagedGroup] = {
    for {
      adminsEmails <- samDAO.listGroupPolicyEmails(groupName, ManagedGroupRoles.Admin)
      membersEmails <- samDAO.listGroupPolicyEmails(groupName, ManagedGroupRoles.Member)
      groupEmail <- samDAO.getGroupEmail(groupName)
    } yield FireCloudManagedGroup(adminsEmails, membersEmails, groupEmail)
  }

}
