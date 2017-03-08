package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.joda.time.DateTime
import spray.http.OAuth2BearerToken
import spray.http.StatusCodes

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
  * Created by davidan on 9/28/16.
  *
  */
class MockRawlsDAO  extends RawlsDAO {

  override def isRegistered(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def isDbGapAuthorized(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = Future(false)

  override def registerUser(userInfo: UserInfo): Future[Unit] = Future(())

  override def getGroupsForUser(implicit userToken: WithAccessToken): Future[Seq[String]] = {
    Future(Seq("TestUserGroup"))
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] = {
    Future(BucketUsageResponse(BigInt("256000000000")))
  }

  private val rawlsWorkspaceWithAttributes = Workspace(
    "attributes",
    "att",
    None, //realm
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Map(AttributeName("default", "a") -> AttributeBoolean(true),
      AttributeName("default", "b") -> AttributeNumber(1.23),
      AttributeName("default", "c") -> AttributeString(""),
      AttributeName("default", "d") -> AttributeString("escape quo\"te"),
      AttributeName("default", "e") -> AttributeString("v1"),
      AttributeName("default", "f") -> AttributeValueList(Seq(
        AttributeString("v6"),
        AttributeNumber(999),
        AttributeBoolean(true)
      ))),
    Map(), //acls
    Map(), //realm acls
    false
  )

  private val publishedRawlsWorkspaceWithAttributes = Workspace(
    "attributes",
    "att",
    None, //realm
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "ansingh",
    Map(AttributeName("default", "a") -> AttributeBoolean(true),
      AttributeName("default", "b") -> AttributeNumber(1.23),
      AttributeName("default", "c") -> AttributeString(""),
      AttributeName("library", "published") -> AttributeBoolean(true),
      AttributeName("library", "projectName") -> AttributeString("testing"),
      AttributeName("default", "d") -> AttributeString("escape quo\"te"),
      AttributeName("default", "e") -> AttributeString("v1"),
      AttributeName("default", "f") -> AttributeValueList(Seq(
        AttributeString("v6"),
        AttributeNumber(999),
        AttributeBoolean(true)
      ))),
    Map(), //acls
    Map(), //realm acls,
    false
  )

  val rawlsWorkspaceResponseWithAttributes = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val publishedRawlsWorkspaceResponseWithAttributes = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] = {
    ns match {
      case "projectowner" => Future(WorkspaceResponse(WorkspaceAccessLevels.ProjectOwner, canShare = true, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "reader" => Future(WorkspaceResponse(WorkspaceAccessLevels.Read, canShare = false, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "attributes" => Future(rawlsWorkspaceResponseWithAttributes)
      case "publishedreader" => Future(WorkspaceResponse(WorkspaceAccessLevels.Read, canShare = false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "publishedwriter" => Future(WorkspaceResponse(WorkspaceAccessLevels.Write, canShare = false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "unpublishedwriter" => Future(WorkspaceResponse(WorkspaceAccessLevels.Write, canShare = false, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case _ => Future(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
    }
  }

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] = {
    Future(Seq(WorkspaceListResponse(WorkspaceAccessLevels.ProjectOwner, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Read, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)))
  }


  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] = {
    Future.successful(newWorkspace)
  }

  private def newWorkspace: Workspace = {
    new Workspace(
      namespace = "namespace",
      name = "name",
      realm = None,
      workspaceId = "workspaceId",
      bucketName = "bucketName",
      createdDate = DateTime.now(),
      lastModified = DateTime.now(),
      createdBy = "createdBy",
      attributes = Map(),
      accessLevels = Map(),
      realmACLs = Map(),
      isLocked = true
      )
  }

  override def getAllLibraryPublishedWorkspaces: Future[Seq[Workspace]] = Future(Seq.empty[Workspace])

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] = {
    Future(WorkspaceACLUpdateResponseList(aclUpdates.map(update => WorkspaceACLUpdateResponse(update.email, update.accessLevel)), aclUpdates, aclUpdates, aclUpdates))
  }

  override def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]] = {
    Future(None)
  }

  override def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit] = {
    Future(())
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "not found")))
    } else {
      Future.successful(Seq.empty)
    }
  }
}
