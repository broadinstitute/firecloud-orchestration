package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{FireCloudManagedGroupMembership, RegistrationInfo, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.client.pipelining.sendReceive
import spray.http.HttpRequest
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 8/21/17.
  */
class HttpSamDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends SamDAO with RestJsonClient {

  override def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Post(samUserRegistrationUrl))
  }

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Get(samUserRegistrationUrl))
  }

  override def adminGetUserByEmail(email: RawlsUserEmail): Future[RegistrationInfo] = {
    adminAuthedRequestToObject[RegistrationInfo](Get(samAdminUserByEmail.format(email.value)))
  }

  override def status: Future[SubsystemStatus] = {
    val pipeline = sendReceive
    pipeline(Get(samStatusUrl)) map { response =>
      val ok = response.status.isSuccess
      SubsystemStatus(ok, if (!ok) Option(List(response.entity.asString)) else None)
    }
  }

  override def createGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequestToUnit(Post(samManagedGroup(groupName)))
  }

  override def deleteGroup(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequestToUnit(Delete(samManagedGroup(groupName)))
  }

  override def listGroups(implicit userInfo: WithAccessToken): Future[List[FireCloudManagedGroupMembership]] = {
    authedRequestToObject[List[FireCloudManagedGroupMembership]](Get(samManagedGroupsBase))
  }

  override def getGroupEmail(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[WorkbenchEmail] = {
    authedRequestToObject[WorkbenchEmail](Get(samManagedGroup(groupName)))
  }

  override def isGroupMember(groupName: WorkbenchGroupName, userInfo: UserInfo): Future[Boolean] = {
    implicit val accessToken = userInfo
    authedRequestToObject[List[String]](Get(samResourceRoles(managedGroupResourceTypeName, groupName.value))).map(_.nonEmpty)
  }

  override def listGroupPolicyEmails(groupName: WorkbenchGroupName, policyName: ManagedGroupRole)(implicit userInfo: WithAccessToken): Future[List[WorkbenchEmail]] = {
    authedRequestToObject[List[WorkbenchEmail]](Get(samManagedGroupPolicy(groupName, policyName)))
  }

  override def addGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequestToUnit(Put(samManagedGroupAlterMember(groupName, role, email)))
  }

  override def removeGroupMember(groupName: WorkbenchGroupName, role: ManagedGroupRole, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequestToUnit(Delete(samManagedGroupAlterMember(groupName, role, email)))
  }

  override def overwriteGroupMembers(groupName: WorkbenchGroupName, role: ManagedGroupRole, memberList: List[WorkbenchEmail])(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequestToUnit(Put(samManagedGroupPolicy(groupName, role), memberList))
  }

  override def addPolicyMember(resourceTypeName: String, resourceId: String, policyName: String, email: WorkbenchEmail)(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequestToUnit(Put(samResourcePolicyAlterMember(resourceTypeName, resourceId, policyName, email)))
  }

  override def requestGroupAccess(groupName: WorkbenchGroupName)(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequestToUnit(Post(samManagedGroupRequestAccess(groupName)))
  }

  private def userAuthedRequestToUnit(request: HttpRequest)(implicit userInfo: WithAccessToken): Future[Unit] = {
    userAuthedRequest(request).map { resp =>
      if(resp.status.isSuccess) ()
      else throw new FireCloudExceptionWithErrorReport(FCErrorReport(resp))
    }
  }

}
