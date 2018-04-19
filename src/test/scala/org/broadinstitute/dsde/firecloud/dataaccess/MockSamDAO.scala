package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.{FireCloudManagedGroupMembership, RegistrationInfo, UserInfo, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}

import scala.concurrent.Future

/**
  * Created by mbemis on 9/7/17.
  */
class MockSamDAO extends SamDAO {

  var groups: Map[WorkbenchGroupName, Set[WorkbenchEmail]] = Map(
    WorkbenchGroupName("TCGA-dbGaP-Authorized") -> Set(WorkbenchEmail("tcga-linked"), WorkbenchEmail("tcga-linked-no-expire-date"), WorkbenchEmail("tcga-linked-expired"), WorkbenchEmail("tcga-linked-user-invalid-expire-date"), WorkbenchEmail("tcga-and-target-linked"), WorkbenchEmail("tcga-and-target-linked-expired")),
    WorkbenchGroupName("TARGET-dbGaP-Authorized") -> Set(WorkbenchEmail("target-linked"), WorkbenchEmail("target-linked-expired"), WorkbenchEmail("tcga-and-target-linked"), WorkbenchEmail("tcga-and-target-linked-expired"))
  )

  override def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = enabledUserInfo

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = enabledUserInfo

  override def adminGetUserByEmail(email: RawlsUserEmail): Future[RegistrationInfo] = customUserInfo(email.value)

  override def status: Future[SubsystemStatus] = Future.successful(SubsystemStatus(ok = true, messages = None))

  override def createGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] = {
    val groupWithMembers = groupName -> Set(WorkbenchEmail(userInfo.accessToken.token)) //ugh

    this.synchronized { groups = groups + groupWithMembers }

    Future.successful(())
  }

  override def deleteGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] = {
    this.synchronized { groups = groups.filterNot(_._1.equals(groupName)) }

    Future.successful(())
  }

  override def listGroups(implicit userInfo: WithAccessToken): Future[List[FireCloudManagedGroupMembership]] = {
    val groupMemberships = groups.keys.map(g => FireCloudManagedGroupMembership(g.value, g.value, "Member"))

    Future.successful(groupMemberships.toList)
  }

  override def getGroupEmail(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[WorkbenchEmail] = {
    Future.successful(WorkbenchEmail(groupName.value))
  }

  override def listGroupPolicyEmails(groupName: WorkbenchGroupName, policyName: ManagedGroupRole)(implicit userInfo: WithAccessToken): Future[List[WorkbenchEmail]] = Future.successful(groups(groupName).toList)

  override def addGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit] = {
    val groupWithNewMembers = groupName -> (groups(groupName).filterNot(_.equals(email)) ++ Set(email))
    this.synchronized { groups = groups + groupWithNewMembers }

    Future.successful(())
  }

  override def removeGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit] = {
    val groupWithNewMembers = groupName -> (groups(groupName).filterNot(_.equals(email)) -- Set(email))
    this.synchronized { groups = groups + groupWithNewMembers }

    Future.successful(())
  }

  override def overwriteGroupMembers(groupName: WorkbenchGroupName, role: ManagedGroupRole, memberList: List[WorkbenchEmail])(implicit userInfo: WithAccessToken): Future[Unit] = {
    val groupWithNewMembers = groupName -> memberList.toSet
    this.synchronized { groups = groups + groupWithNewMembers }

    Future.successful(())
  }

  private val enabledUserInfo = Future.successful {
    RegistrationInfo(
      WorkbenchUserInfo(userSubjectId = "foo", userEmail = "bar"),
      WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true))
  }

  private def customUserInfo(email: String) = Future.successful {
    RegistrationInfo(
      WorkbenchUserInfo(email, email),
      WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true))
  }

  override def isGroupMember(groupName: WorkbenchGroupName, userInfo: UserInfo): Future[Boolean] = {
    Future.successful(groups.getOrElse(groupName, Set.empty).contains(WorkbenchEmail(userInfo.userEmail)))
  }

  override def requestGroupAccess(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] = {
    Future.successful(()) //not really a good way to mock this at the moment, TODO
  }

  override def addPolicyMember(resourceTypeName: String, resourceId: String, policyName: String, email: WorkbenchEmail)(implicit userInfo: WithAccessToken) = Future.successful(()) //todo
}
