package org.broadinstitute.dsde.firecloud.service

/**
  * Created by mbemis on 3/26/18.
  */

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.SamDAO
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.{FireCloudManagedGroup, FireCloudManagedGroupMembership, ManagedGroupRoles, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.ManagedGroupService._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object ManagedGroupService {

  def constructor(app: Application)(userInfo: WithAccessToken)(implicit executionContext: ExecutionContext) =
    new ManagedGroupService(app.samDAO, userInfo)

}

class ManagedGroupService(samDAO: SamDAO, implicit val userToken: WithAccessToken)(implicit protected val executionContext: ExecutionContext)
  extends LazyLogging with SprayJsonSupport {

  def createGroup(groupName: WorkbenchGroupName): Future[PerRequestMessage] = {
    val membersList = for {
      _ <- samDAO.createGroup(groupName)
      listMembers <- listGroupMembersInternal(groupName)
      _ <- samDAO.setPolicyPublic(samDAO.managedGroupResourceTypeName, groupName.value, ManagedGroupRoles.AdminNotifier.toString, true)
    } yield listMembers
    membersList.map(response => RequestComplete(StatusCodes.Created, response))
  }

  def deleteGroup(groupName: WorkbenchGroupName): Future[PerRequestMessage] = {
    samDAO.deleteGroup(groupName).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def listGroups(): Future[PerRequestMessage] = {
    samDAO.listGroups.map(response => RequestComplete(StatusCodes.OK, response.map { group =>
       group.copy(role = group.role.capitalize)
    }))
  }

  def listGroupMembers(groupName: WorkbenchGroupName): Future[PerRequestMessage] = {
    listGroupMembersInternal(groupName).map(response => RequestComplete(StatusCodes.OK, response))
  }

  def addGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail): Future[PerRequestMessage] = {
    samDAO.addGroupMember(groupName, role, email).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def removeGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail): Future[PerRequestMessage] = {
    samDAO.removeGroupMember(groupName, role, email).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def overwriteGroupMembers(groupName: WorkbenchGroupName, role: ManagedGroupRole, membersList: List[WorkbenchEmail]): Future[PerRequestMessage] = {
    samDAO.overwriteGroupMembers(groupName, role, membersList).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  def requestGroupAccess(groupName: WorkbenchGroupName): Future[PerRequestMessage] = {
    samDAO.requestGroupAccess(groupName).map(_ => RequestComplete(StatusCodes.NoContent))
  }

  private def listGroupMembersInternal(groupName: WorkbenchGroupName): Future[FireCloudManagedGroup] = {
    for {
      adminsEmails <- samDAO.listGroupPolicyEmails(groupName, ManagedGroupRoles.Admin)
      membersEmails <- samDAO.listGroupPolicyEmails(groupName, ManagedGroupRoles.Member)
      groupEmail <- samDAO.getGroupEmail(groupName)
    } yield FireCloudManagedGroup(adminsEmails, membersEmails, groupEmail)
  }
}
