package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions._
import org.broadinstitute.dsde.rawls.model.WorkspaceACLJsonSupport._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.joda.time.DateTime
import spray.client.pipelining._
import spray.http.StatusCodes._
import spray.http.{HttpResponse, OAuth2BearerToken}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by davidan on 9/23/16.
  */
class HttpRawlsDAO( implicit val system: ActorSystem, implicit val executionContext: ExecutionContext )
  extends RawlsDAO with RestJsonClient {

  override def isRegistered(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest(Get(rawlsUserRegistrationUrl))(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(ErrorReport(response))
      }
    }
  }

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest( Get(rawlsAdminUrl) )(userInfo) map { response =>
      response.status match {
        case OK => true
        case NotFound => false
        case _ => throw new FireCloudExceptionWithErrorReport(FCErrorReport(response))
      }
    }
  }

  override def isDbGapAuthorized(userInfo: UserInfo): Future[Boolean] = {
    userAuthedRequest(Get(RawlsDAO.groupUrl(FireCloudConfig.Nih.rawlsGroupName)))(userInfo) map {
      response => response.status match {
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
    requestToObject[Seq[String]]( Get(rawlsGroupsForUserUrl) )

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[RawlsBucketUsageResponse] =
    requestToObject[RawlsBucketUsageResponse]( Get(rawlsBucketUsageUrl(ns, name)) )

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] =
    requestToObject[WorkspaceResponse]( Get(getWorkspaceUrl(ns, name)) )

  override def patchWorkspaceAttributes(ns: String, name: String, attributeOperations: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] = {
    import spray.json.DefaultJsonProtocol._
    import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperationFormat
    import spray.json.DefaultJsonProtocol._
    requestToObject[Workspace]( Patch(getWorkspaceUrl(ns, name), attributeOperations) )
  }

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate],inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] =
    requestToObject[WorkspaceACLUpdateResponseList]( Patch(patchWorkspaceAclUrl(ns, name, inviteUsersNotFound), aclUpdates) )

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

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    requestToObject[Seq[Entity]](Get(rawlsEntitiesOfTypeUrl(workspaceNamespace, workspaceName, entityType)), true)
  }

  private def getWorkspaceUrl(ns: String, name: String) = FireCloudConfig.Rawls.authUrl + FireCloudConfig.Rawls.workspacesPath + s"/%s/%s".format(ns, name)
  private def patchWorkspaceAclUrl(ns: String, name: String, inviteUsersNotFound: Boolean) = rawlsWorkspaceACLUrl.format(ns, name, inviteUsersNotFound)

  override def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]] = {
    userAuthedRequest(Get(RawlsDAO.refreshTokenDateUrl))(userInfo) map { response =>
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
}
