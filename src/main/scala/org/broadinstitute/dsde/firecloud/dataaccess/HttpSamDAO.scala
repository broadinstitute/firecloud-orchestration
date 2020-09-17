package org.broadinstitute.dsde.firecloud.dataaccess

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.broadinstitute.dsde.firecloud.model.{AccessToken, FireCloudManagedGroupMembership, ManagedGroupRoles, RegistrationInfo, RegistrationInfoV2, UserIdInfo, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.DefaultJsonProtocol._
import spray.json.{JsValue, JsonFormat, RootJsonFormat}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 8/21/17.
  */
class HttpSamDAO( implicit val system: ActorSystem, val materializer: Materializer, implicit val executionContext: ExecutionContext )
  extends SamDAO with RestJsonClient with SprayJsonSupport {

  override val http = Http(system)

  override def listWorkspaceResources(implicit userInfo: WithAccessToken): Future[Seq[UserPolicy]] = {
    authedRequestToObject[Seq[UserPolicy]](Get(samListResources("workspace")), label=Some("HttpSamDAO.listWorkspaceResources"))
  }

  override def registerUser(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Post(samUserRegistrationUrl), label=Some("HttpSamDAO.registerUser"))
  }

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Get(samUserRegistrationUrl), label=Some("HttpSamDAO.getRegistrationStatus"))
  }

  override def getRegistrationStatusV2(implicit userInfo: WithAccessToken): Future[Option[RegistrationInfoV2]] = ???

  override def getUserIds(email: RawlsUserEmail)(implicit userInfo: WithAccessToken): Future[UserIdInfo] = {
    authedRequestToObject[UserIdInfo](Get(samGetUserIdsUrl.format(URLEncoder.encode(email.value, UTF_8.name))))
  }

  override def isGroupMember(groupName: WorkbenchGroupName, userInfo: UserInfo): Future[Boolean] = {
    implicit val accessToken = userInfo
    authedRequestToObject[List[String]](Get(samResourceRoles(managedGroupResourceTypeName, groupName.value)), label=Some("HttpSamDAO.isGroupMember")).map { allRoles =>
      allRoles.map(ManagedGroupRoles.withName).toSet.intersect(ManagedGroupRoles.membershipRoles).nonEmpty
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

  override def setPolicyPublic(resourceTypeName: String, resourceId: String, policyName: String, public: Boolean)(implicit userInfo: WithAccessToken): Future[Unit] = {
    implicit val booleanFormat = new RootJsonFormat[Boolean] {
      override def read(json: JsValue): Boolean = implicitly[JsonFormat[Boolean]].read(json)
      override def write(obj: Boolean): JsValue = implicitly[JsonFormat[Boolean]].write(obj)
    }

    userAuthedRequestToUnit(Put(samResourcePolicy(resourceTypeName, resourceId, policyName) + "/public", public))
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

  override def getPetServiceAccountTokenForUser(user: WithAccessToken, scopes: Seq[String]): Future[AccessToken] = {
    implicit val accessToken = user

    authedRequestToObject[String](Post(samArbitraryPetTokenUrl, scopes), label=Some("HttpSamDAO.getPetServiceAccountTokenForUser")).map { quotedToken =>
      // Sam returns a quoted string. We need the token without the quotes.
      val token = if (quotedToken.startsWith("\"") && quotedToken.endsWith("\"") )
        quotedToken.substring(1,quotedToken.length-1)
      else
        quotedToken
      AccessToken.apply(token)
    }
  }

  //TODO: do I really need to break out the singleRequest for this?
  override def status: Future[SubsystemStatus] = {
    for {
      response <- http.singleRequest(Get(samStatusUrl))
      ok = response.status.isSuccess
      message <- if (ok) Future.successful(None) else Unmarshal(response.entity).to[String].map(Option(_))
    } yield {
      SubsystemStatus(ok, message.map(List(_)))
    }
  }
}
