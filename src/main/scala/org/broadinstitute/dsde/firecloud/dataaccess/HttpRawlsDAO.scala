package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Rawls
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions._
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.AgoraConfigurationShort
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impRawlsBillingProjectMember, _}
import org.broadinstitute.dsde.firecloud.model.Project.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Project.{RawlsBillingProjectMember, RawlsBillingProjectMembership}
import org.broadinstitute.dsde.firecloud.model.{EntityUpdateDefinition, _}
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectiveUtils
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.rawls.model.StatusJsonSupport._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.rawls.model.{StatusCheckResponse => RawlsStatus, SubsystemStatus => RawlsSubsystemStatus, _}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.joda.time.DateTime
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Created by davidan on 9/23/16.
  */
class HttpRawlsDAO(implicit val system: ActorSystem, implicit val materializer: Materializer, implicit val executionContext: ExecutionContext)
  extends RawlsDAO with RestJsonClient with SprayJsonSupport {

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest(Get(rawlsAdminUrl))(userInfo) flatMap { response =>
      response.status match {
        case OK => Future.successful(true)
        case NotFound => Future.successful(false)
        case _ => {
          FCErrorReport(response).flatMap { errorReport =>
            Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
          }
        }
      }
    }
  }

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest(Get(rawlsCuratorUrl))(userInfo) flatMap { response =>
      response.status match {
        case OK => Future.successful(true)
        case NotFound => Future.successful(false)
        case _ => {
          FCErrorReport(response).flatMap { errorReport =>
            Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
          }
        }
      }
    }
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] =
    authedRequestToObject[BucketUsageResponse](Get(rawlsBucketUsageUrl(ns, name)))

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] =
    authedRequestToObject[Seq[WorkspaceListResponse]](Get(rawlsWorkpacesUrl), label = Some("HttpRawlsDAO.getWorkspaces"))

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] =
    authedRequestToObject[WorkspaceResponse](Get(getWorkspaceUrl(ns, name)))

  override def patchWorkspaceAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] =
    authedRequestToObject[WorkspaceDetails](Patch(getWorkspaceUrl(ns, name), attributeOperations))

  override def updateLibraryAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[WorkspaceDetails] =
    authedRequestToObject[WorkspaceDetails](Patch(getWorkspaceUrl(ns, name) + "/library", attributeOperations))

  override def getWorkspaceACL(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceACL] =
    authedRequestToObject[WorkspaceACL](Get(getWorkspaceAclUrl(ns, name)))

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] =
    authedRequestToObject[WorkspaceACLUpdateResponseList](Patch(patchWorkspaceAclUrl(ns, name, inviteUsersNotFound), aclUpdates))

  // you must be an admin to execute this method
  override def getAllLibraryPublishedWorkspaces(implicit userToken: WithAccessToken): Future[Seq[WorkspaceDetails]] = {
    userAuthedRequest(Get(rawlsAdminWorkspaces)).flatMap { response =>
      if(response.status.isSuccess()) {
        Unmarshal(response).to[Seq[WorkspaceDetails]].map { srw =>
          logger.info("admin workspace list reindexing: " + srw.length + " published workspaces")
          srw
        }
      }
      else {
        logger.info(s"body of reindex error response: ${response.entity}")
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Could not unmarshal: " + response.entity))
      }
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

  private def getWorkspaceCloneUrl(ns: String, name: String) = encodeUri(FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/$ns/$name/clone")

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

  override def getProjects(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMembership]] = {
    authedRequestToObject[Seq[RawlsBillingProjectMembership]](Get(FireCloudConfig.Rawls.authUrl + "/user/billing"))
  }

  override def getProjectMembers(projectId: String)(implicit userToken: WithAccessToken): Future[Seq[RawlsBillingProjectMember]] = {
    authedRequestToObject[Seq[RawlsBillingProjectMember]](Get(FireCloudConfig.Rawls.authUrl + s"/billing/$projectId/members"), true)
  }

  override def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
    val url = editBillingMembershipURL(projectId, role, email)

    userAuthedRequest(Put(url), true) flatMap { resp =>
      if (resp.status.isSuccess) {
        Future.successful(true)
      } else {
        FCErrorReport(resp).flatMap { errorReport =>
          Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
        }
      }
    }
  }

  override def removeUserFromBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
    val url = editBillingMembershipURL(projectId, role, email)

    userAuthedRequest(Delete(url), true) flatMap { resp =>
      if (resp.status.isSuccess) {
        Future.successful(true)
      } else {
        FCErrorReport(resp).flatMap { errorReport =>
          Future.failed(new FireCloudExceptionWithErrorReport(errorReport))
        }
      }
    }
  }

  override def batchUpsertEntities(workspaceNamespace: String, workspaceName: String, entityType: String, upserts: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse] = {
    val request = Post(FireCloudDirectiveUtils.encodeUri(Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)+"/batchUpsert"),
      HttpEntity(MediaTypes.`application/json`,upserts.toJson.toString))

    userAuthedRequest(request)
  }

  override def batchUpdateEntities(workspaceNamespace: String, workspaceName: String, entityType: String, updates: Seq[EntityUpdateDefinition])(implicit userToken: UserInfo): Future[HttpResponse] = {
    val request = Post(FireCloudDirectiveUtils.encodeUri(Rawls.entityPathFromWorkspace(workspaceNamespace, workspaceName)+"/batchUpdate"),
      HttpEntity(MediaTypes.`application/json`,updates.toJson.toString))

    userAuthedRequest(request)
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

  override def deleteWorkspace(workspaceNamespace: String, workspaceName: String)(implicit userToken: WithAccessToken): Future[Option[String]] = {
    authedRequestToObject[Option[String]](Delete(getWorkspaceUrl(workspaceNamespace, workspaceName)))
  }

  override def cloneWorkspace(workspaceNamespace: String, workspaceName: String, cloneRequest: WorkspaceRequest)(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = {
    authedRequestToObject[WorkspaceDetails](Post(getWorkspaceCloneUrl(workspaceNamespace, workspaceName), cloneRequest))
  }

}
