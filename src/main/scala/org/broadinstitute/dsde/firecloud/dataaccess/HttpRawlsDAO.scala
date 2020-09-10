package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraConfigurationShort
import org.broadinstitute.dsde.firecloud.model.Metrics.AdminStats
import org.broadinstitute.dsde.firecloud.model.MetricsFormat.AdminStatsFormat
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Project.{CreateRawlsBillingProjectFullRequest, RawlsBillingProjectMember, RawlsBillingProjectMembership}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.rawls.model.StatusJsonSupport._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model.{StatusCheckResponse => RawlsStatus, SubsystemStatus => RawlsSubsystemStatus, _}
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.joda.time.DateTime
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{OAuth2BearerToken, StatusCodes, Uri}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsBillingProjectMember
import org.broadinstitute.dsde.firecloud.model.Project.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectiveUtils
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

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsCuratorUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] =
    authedRequestToObject[BucketUsageResponse]( Get(rawlsBucketUsageUrl(ns, name)) )

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] =
    authedRequestToObject[Seq[WorkspaceListResponse]] ( Get(rawlsWorkpacesUrl), label=Some("HttpRawlsDAO.getWorkspaces") )

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] =
    authedRequestToObject[WorkspaceResponse]( Get(getWorkspaceUrl(ns, name)) )

  override def patchWorkspaceAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] =
    authedRequestToObject[WorkspaceDetails]( Patch(getWorkspaceUrl(ns, name), attributeOperations) )

  override def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] =
    authedRequestToObject[WorkspaceDetails]( Patch(getWorkspaceUrl(ns, name)+"/library", attributeOperations) )

  override def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceACL] =
    authedRequestToObject[WorkspaceACL]( Get(getWorkspaceAclUrl(ns, name)) )

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate],inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] =
    authedRequestToObject[WorkspaceACLUpdateResponseList]( Patch(patchWorkspaceAclUrl(ns, name, inviteUsersNotFound), aclUpdates) )

  // you must be an admin to execute this method
  override def getAllLibraryPublishedWorkspaces(implicit userToken: WithAccessToken): Future[Seq[WorkspaceDetails]] = {

    val allPublishedPipeline = addCredentials(userToken.accessToken) ~> sendReceive
    allPublishedPipeline(Get(rawlsAdminWorkspaces)) map {response =>
      response.entity.as[Seq[WorkspaceDetails]] match {
        case Right(srw) =>
          logger.info("admin workspace list reindexing: " + srw.length + " published workspaces")
          srw
        case Left(error) =>
          logger.warn(s"Could not unmarshal: ${error.toString}. Status code: ${response.status}.")
          logger.info(s"body of reindex error response: ${response.entity}")
          throw new FireCloudExceptionWithErrorReport(ErrorReport(InternalServerError, "Could not unmarshal: " + error.toString))
      }
    }
  }

  override def adminStats(startDate: DateTime, endDate: DateTime, workspaceNamespace: Option[String], workspaceName: Option[String]): Future[AdminStats] = {
    val queryParams =
      Map("startDate" -> startDate.toString, "endDate" -> endDate.toString) ++
        workspaceNamespace.map("workspaceNamespace" -> _) ++
        workspaceName.map("workspaceName" -> _)
    val url = Uri(FireCloudConfig.Rawls.authUrl + "/admin/statistics").withQuery(queryParams)
    adminAuthedRequestToObject[AdminStats](Get(url)) recover {
      case e:Exception =>
        logger.error(s"HttpRawlsDAO.adminStats failed with ${e.getMessage}")
        throw e
    }
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    authedRequestToObject[Seq[Entity]](Get(rawlsEntitiesOfTypeUrl(workspaceNamespace, workspaceName, entityType)), true)
  }

  override def queryEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String, query: EntityQuery)(implicit userToken: UserInfo): Future[EntityQueryResponse] = {
    val targetUri = FireCloudConfig.Rawls.entityQueryUriFromWorkspaceAndQuery(workspaceNamespace, workspaceName, entityType, Some(query))
    authedRequestToObject[EntityQueryResponse](Get(targetUri), compressed = true)
  }

  override def getEntityTypes(workspaceNamespace: String, workspaceName: String)(implicit userToken: UserInfo): Future[Map[String, EntityTypeMetadata]] = {
    val url = encodeUri(FireCloudConfig.Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName))
    authedRequestToObject[Map[String, EntityTypeMetadata]](Get(url), compressed = true)
  }

  private def getWorkspaceUrl(ns: String, name: String) = encodeUri(FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/$ns/$name")
  private def getWorkspaceAclUrl(ns: String, name: String) = encodeUri(rawlsWorkspaceACLUrl(ns, name))
  private def patchWorkspaceAclUrl(ns: String, name: String, inviteUsersNotFound: Boolean) = rawlsWorkspaceACLUrl(ns, name) + rawlsWorkspaceACLQuerystring.format(inviteUsersNotFound)
  private def workspaceCatalogUrl(ns: String, name: String) = encodeUri(FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/$ns/$name/catalog")

  override def getCatalog(ns: String, name: String)(implicit userToken: WithAccessToken): Future[Seq[WorkspaceCatalog]] =
    authedRequestToObject[Seq[WorkspaceCatalog]](Get(workspaceCatalogUrl(ns, name)), true)

  override def patchCatalog(ns: String, name: String, catalogUpdates: Seq[WorkspaceCatalog])(implicit userToken: WithAccessToken): Future[WorkspaceCatalogUpdateResponseList] =
    authedRequestToObject[WorkspaceCatalogUpdateResponseList](Patch(workspaceCatalogUrl(ns, name), catalogUpdates), true)

  // If we ever need to getAllMethodConfigs, that's Uri(rawlsWorkspaceMethodConfigsUrl.format(ns, name)).withQuery("allRepos" -> "true")
  override def getAgoraMethodConfigs(ns: String, name: String)(implicit userToken: WithAccessToken): Future[Seq[AgoraConfigurationShort]] = {
    authedRequestToObject[Seq[AgoraConfigurationShort]](Get(rawlsWorkspaceMethodConfigsUrl(ns, name)), true)
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

  override def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
    val url = editBillingMembershipURL(projectId, role, email)

    userAuthedRequest(Put(url), true) map { resp =>
      if (resp.status.isSuccess) {
        true
      } else {
        throw new FireCloudExceptionWithErrorReport(FCErrorReport(resp))
      }
    }
  }

  override def removeUserFromBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
    val url = editBillingMembershipURL(projectId, role, email)

    userAuthedRequest(Delete(url), true) map { resp =>
      if (resp.status.isSuccess) {
        true
      } else {
        throw new FireCloudExceptionWithErrorReport(FCErrorReport(resp))
      }
    }
  }

  private def editBillingMembershipURL(projectId: String, role: ProjectRole, email: String) = {
    FireCloudConfig.Rawls.authUrl + s"/billing/$projectId/${role.toString}/${java.net.URLEncoder.encode(email, "UTF-8")}"
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
    authedRequestToObject[WorkspaceDeleteResponse]( Delete(getWorkspaceUrl(workspaceNamespace, workspaceName)) )
  }

}
