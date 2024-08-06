package org.broadinstitute.dsde.firecloud.dataaccess

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.ManagedGroupRoles.ManagedGroupRole
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.broadinstitute.dsde.firecloud.model.{AccessToken, FireCloudManagedGroupMembership, ManagedGroupRoles, RegistrationInfo, RegistrationInfoV2, SamUserAttributesRequest, SamUserRegistrationRequest, SamUserResponse, UserIdInfo, UserInfo, WithAccessToken, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import org.broadinstitute.dsde.workbench.client.sam.model.User
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.DefaultJsonProtocol._
import spray.json.{JsValue, JsonFormat, RootJsonFormat}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 8/21/17.
  */
class HttpSamDAO( implicit val system: ActorSystem, val materializer: Materializer, implicit val executionContext: ExecutionContext )
  extends SamDAO with RestJsonClient with SprayJsonSupport {

  override def listWorkspaceResources(implicit userInfo: WithAccessToken): Future[Seq[UserPolicy]] = {
    authedRequestToObject[Seq[UserPolicy]](Get(samListResources("workspace")), label=Some("HttpSamDAO.listWorkspaceResources"))
  }

  override def registerUser(termsOfService: Option[String])(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Post(samUserRegistrationUrl, termsOfService), label=Some("HttpSamDAO.registerUser"))
  }

  override def registerUserSelf(acceptsTermsOfService: Boolean)(implicit userInfo: WithAccessToken): Future[SamUserResponse] = {
    authedRequestToObject[SamUserResponse](Post(samUserRegisterSelfUrl, SamUserRegistrationRequest(acceptsTermsOfService, SamUserAttributesRequest(marketingConsent = Some(false)))), label = Some("HttpSamDAO.registerUserSelf"))
  }

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    authedRequestToObject[RegistrationInfo](Get(samUserRegistrationUrl), label=Some("HttpSamDAO.getRegistrationStatus"))
  }

  override def getUserIds(email: RawlsUserEmail)(implicit userInfo: WithAccessToken): Future[UserIdInfo] = {
    authedRequestToObject[UserIdInfo](Get(samGetUserIdsUrl.format(URLEncoder.encode(email.value, UTF_8.name))))
  }


  // Sam's API only allows for 1000 user to be fetched at one time
  override def getUsersForIds(samUserIds: Seq[WorkbenchUserId])(implicit userInfo: WithAccessToken): Future[Seq[WorkbenchUserInfo]] = Future.sequence {
      samUserIds.sliding(1000).toSeq.map { batch =>
        adminAuthedRequestToObject[Seq[SamUserResponse]](Post(samAdminGetUsersForIdsUrl, batch))
          .map(_.map(user => WorkbenchUserInfo(user.id.value, user.email.value)))
      }
    }.map(_.flatten)

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
    userAuthedRequest(request).flatMap { resp =>
      if(resp.status.isSuccess) Future.successful({
        resp.discardEntityBytes()
      })
      else {
        FCErrorReport(resp).flatMap { errorReport =>
          Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
        }
      }
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


  def getPetServiceAccountKeyForUser(user: WithAccessToken, project: GoogleProject): Future[String] = {
    implicit val accessToken = user

    authedRequestToObject[String](Get(samPetKeyForProject.format(project.value)), label=Some("HttpSamDAO.getPetServiceAccountKeyForUser"))
  }

  override def status: Future[SubsystemStatus] = {
    for {
      response <- unAuthedRequest(Get(samStatusUrl))
      ok = response.status.isSuccess
      message <- if (ok) Future.successful(None) else Unmarshal(response.entity).to[String].map(Option(_))
    } yield {
      SubsystemStatus(ok, message.map(List(_)))
    }
  }
}
