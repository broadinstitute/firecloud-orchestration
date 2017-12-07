package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Trial.{CreateRawlsBillingProjectFullRequest, RawlsBillingProjectMember, RawlsBillingProjectMembership}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.rawls.model.StatusJsonSupport._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model.{StatusCheckResponse => RawlsStatus, SubsystemStatus => RawlsSubsystemStatus, _}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.joda.time.DateTime
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{OAuth2BearerToken, StatusCodes, Uri}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsBillingProjectMember
import org.broadinstitute.dsde.firecloud.model.Trial.ProjectRoles.ProjectRole
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Created by davidan on 9/23/16.
  */
class HttpRawlsDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends RawlsDAO with RestJsonClient {

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsAdminUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }

  override def isGroupMember(userInfo: UserInfo, groupName: String): Future[Boolean] = {
    userAuthedRequest(Get(RawlsDAO.groupUrl(groupName)))(userInfo) map { response =>
      response.status match {
        case OK => true
        case _ => false
      }
    }
  }

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsCuratorUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }

  override def registerUser(userInfo: UserInfo): Future[Unit] = {
    userAuthedRequest(Post(rawlsUserRegistrationUrl))(userInfo) map { _ => () }
  }

  override def getGroupsForUser(implicit userToken: WithAccessToken): Future[Seq[String]] =
    authedRequestToObject[Seq[String]]( Get(rawlsGroupsForUserUrl), label=Some("HttpRawlsDAO.getGroupsForUser") )

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] =
    authedRequestToObject[BucketUsageResponse]( Get(rawlsBucketUsageUrl(ns, name)) )

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] =
    authedRequestToObject[Seq[WorkspaceListResponse]] ( Get(rawlsWorkpacesUrl), label=Some("HttpRawlsDAO.getWorkspaces") )

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] =
    authedRequestToObject[WorkspaceResponse]( Get(getWorkspaceUrl(ns, name)) )

  override def patchWorkspaceAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] =
    authedRequestToObject[Workspace]( Patch(getWorkspaceUrl(ns, name), attributeOperations) )

  override def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] =
    authedRequestToObject[Workspace]( Patch(getWorkspaceUrl(ns, name)+"/library", attributeOperations) )

  override def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceACL] =
    authedRequestToObject[WorkspaceACL]( Get(getWorkspaceAclUrl(ns, name)) )

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate],inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] =
    authedRequestToObject[WorkspaceACLUpdateResponseList]( Patch(patchWorkspaceAclUrl(ns, name, inviteUsersNotFound), aclUpdates) )

  override def getAllLibraryPublishedWorkspaces: Future[Seq[Workspace]] = {
    val adminToken = HttpGoogleServicesDAO.getAdminUserAccessToken

    val allPublishedPipeline = addCredentials(OAuth2BearerToken(adminToken)) ~> sendReceive
    allPublishedPipeline(Get(rawlsAdminWorkspaces)) map {response =>
      response.entity.as[Seq[Workspace]] match {
        case Right(srw) =>
          logger.info("admin workspace list reindexing: " + srw.length + " published workspaces")
          srw
        case Left(error) =>
          logger.warn("Could not unmarshal: " + error.toString)
          throw new FireCloudExceptionWithErrorReport(ErrorReport(InternalServerError, "Could not unmarshal: " + error.toString))
      }
    }
  }

  override def adminAddMemberToGroup(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean] = {
    val url = FireCloudConfig.Rawls.overwriteGroupMembershipUrlFromGroupName(groupName)

    adminAuthedRequest(Post(url, memberList)).map(_.status.isSuccess)
  }

  override def adminOverwriteGroupMembership(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean] = {
    val url = FireCloudConfig.Rawls.overwriteGroupMembershipUrlFromGroupName(groupName)

    adminAuthedRequest(Put(url, memberList)).map(_.status.isSuccess)
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    authedRequestToObject[Seq[Entity]](Get(rawlsEntitiesOfTypeUrl(workspaceNamespace, workspaceName, entityType)), true)
  }

  override def queryEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery)(implicit userToken: UserInfo): Future[EntityQueryResponse] = {
    val targetUri = FireCloudConfig.Rawls.entityQueryUriFromWorkspaceAndQuery(workspaceNamespace, workspaceName, entityType, Some(query))
    authedRequestToObject[EntityQueryResponse](Get(targetUri), compressed = true)
  }

  override def getEntityTypes(workspaceNamespace: String, workspaceName: String)(implicit userToken: UserInfo): Future[Map[String, EntityTypeMetadata]] = {
    val url = FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)
    authedRequestToObject[Map[String, EntityTypeMetadata]](Get(url), compressed = true)
  }

  private def getWorkspaceUrl(ns: String, name: String) = FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/%s/%s".format(ns, name)
  private def getWorkspaceAclUrl(ns: String, name: String) = rawlsWorkspaceACLUrl.format(ns, name)
  private def patchWorkspaceAclUrl(ns: String, name: String, inviteUsersNotFound: Boolean) = rawlsWorkspaceACLUrl.format(ns, name) + rawlsWorkspaceACLQuerystring.format(inviteUsersNotFound)
  private def workspaceCatalogUrl(ns: String, name: String) = FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/%s/%s/catalog".format(ns, name)

  override def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]] = {
    userAuthedRequest(Get(RawlsDAO.refreshTokenDateUrl), label=Some("HttpRawlsDAO.getRefreshTokenStatus"))(userInfo) map { response =>
      response.status match {
        case OK =>
          Option(DateTime.parse(unmarshal[RawlsTokenDate].apply(response).refreshTokenUpdatedDate))
        case NotFound | BadRequest => None
        case _ => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }

  override def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit] = {
    userAuthedRequest(Put(RawlsDAO.refreshTokenUrl, RawlsToken(refreshToken)))(userInfo) map
      { _ => () }
  }

  override def getCatalog(ns: String, name: String)(implicit userToken: WithAccessToken): Future[Seq[WorkspaceCatalog]] =
    authedRequestToObject[Seq[WorkspaceCatalog]](Get(workspaceCatalogUrl(ns, name)), true)

  override def patchCatalog(ns: String, name: String, catalogUpdates: Seq[WorkspaceCatalog])(implicit userToken: WithAccessToken): Future[WorkspaceCatalogUpdateResponseList] =
    authedRequestToObject[WorkspaceCatalogUpdateResponseList](Patch(workspaceCatalogUrl(ns, name), catalogUpdates), true)

  override def getMethodConfigs(ns: String, name: String)(implicit userToken: WithAccessToken): Future[Seq[MethodConfigurationShort]] = {
    authedRequestToObject[Seq[MethodConfigurationShort]](Get(rawlsWorkspaceMethodConfigsUrl.format(ns, name)), true)
  }

  override def createProject(projectName: String, billingAccount: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
    val create = CreateRawlsBillingProjectFullRequest(projectName, billingAccount)
    userAuthedRequest(Post(FireCloudConfig.Rawls.authUrl + "/billing", create)).map { resp =>
      resp.status.isSuccess
    }
  }

  override def getProjects(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMembership]] = {
    userAuthedRequest(Get(FireCloudConfig.Rawls.authUrl + "/user/billing")).map { resp =>
      resp.entity.as[Seq[RawlsBillingProjectMembership]] match {
        case Right(obj) => obj
        case Left(error) => throw new FireCloudExceptionWithErrorReport(FCErrorReport(resp))
      }
    }
  }

  override def getProjectMembers(projectId: String)(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMember]] = {
    authedRequestToObject[Seq[RawlsBillingProjectMember]](Get(FireCloudConfig.Rawls.authUrl + s"/billing/$projectId/members"), true)
  }

  override def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Unit] = {
    userAuthedRequest(Get(FireCloudConfig.Rawls.authUrl + s"/billing/$projectId/${role.toString}/$email"), true) map { resp =>
      resp.status match {
        case OK => ()
        case _ => throw new FireCloudExceptionWithErrorReport(FCErrorReport(resp))
      }
    }
  }

  override def status: Future[SubsystemStatus] = {
    val rawlsStatus = unAuthedRequestToObject[RawlsStatus](Get(Uri(FireCloudConfig.Rawls.baseUrl).withPath(Uri.Path("/status"))))

    def parseRawlsMessages(rs: RawlsStatus): Option[List[String]] = {
      val rawlsMessages = rs.systems.toList.flatMap {
        case (k, RawlsSubsystemStatus(subsystem, Some(messages))) if messages.nonEmpty =>
          Some(s"$k: ${messages.mkString(", ")}")
        case _ => None
      }
      if (rawlsMessages.nonEmpty) Some(rawlsMessages) else None
    }

    rawlsStatus.map { status =>
      SubsystemStatus(status.ok, parseRawlsMessages(status))
    }.recoverWith { case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Some(StatusCodes.InternalServerError) =>
      // Rawls returns 500 on status check failures, but the JSON data should still be sent in the
      // response body and stored in the ErrorReport. Try to parse a RawlsStatus from the error report
      // (if it exists) so we can display it to the user. If this fails, then we will recover from the error below.
      Future(e.errorReport.message.parseJson.convertTo[RawlsStatus]).map { recoveredStatus =>
        SubsystemStatus(recoveredStatus.ok, parseRawlsMessages(recoveredStatus))
      }
    }.recover {
      case NonFatal(e) => SubsystemStatus(false, Some(List(e.getMessage)))
    }
  }

  def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[WorkspaceDeleteResponse] = {
    authedRequestToObject[WorkspaceDeleteResponse]( Delete(s"${FireCloudConfig.Rawls.authUrl}/workspaces/$workspaceNamespace/$workspaceName") )
  }

}
