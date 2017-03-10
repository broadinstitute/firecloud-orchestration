package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.joda.time.DateTime
import spray.http.{HttpResponse, OAuth2BearerToken, StatusCodes}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
  * Created by davidan on 9/28/16.
  *
  */
class MockRawlsDAO  extends RawlsDAO {

  var groups: Map[String, Set[String]] = Map(FireCloudConfig.Nih.rawlsGroupName -> Set("linked-user", "linked-user-expired-link", "linked-user-no-expire-date", "linked-user-invalid-expire-date"))

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

  override def isRegistered(userInfo: UserInfo): Future[Boolean] = Future.successful(true)

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = Future.successful(true)

  override def isDbGapAuthorized(userInfo: UserInfo): Future[Boolean] = Future.successful(true)

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = Future.successful(true)

  override def registerUser(userInfo: UserInfo): Future[Unit] = Future.successful(())

  override def adminAddMemberToGroup(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean] = {
    val userEmailsToAdd = memberList.userSubjectIds.getOrElse(Seq[String]()).toSet
    val groupWithNewMembers = (groupName -> ((groups(groupName).filterNot(userEmailsToAdd.contains)) ++ userEmailsToAdd))
    groups = groups + groupWithNewMembers

    Future.successful(true)
  }

  override def adminOverwriteGroupMembership(groupName: String, memberList: RawlsGroupMemberList): Future[Boolean] = {
    val userEmailsToAdd = memberList.userSubjectIds.getOrElse(Set[String]()).toSet
    val groupWithNewMembers = (groupName -> userEmailsToAdd)
    groups = groups + groupWithNewMembers

    Future.successful(true)
  }

  override def getGroupsForUser(implicit userToken: WithAccessToken): Future[Seq[String]] = {
    Future.successful(Seq("TestUserGroup"))
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[BucketUsageResponse] = {
    Future.successful(BucketUsageResponse(BigInt("256000000000")))
  }

  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[WorkspaceResponse] = {
    ns match {
      case "projectowner" => Future(WorkspaceResponse(WorkspaceAccessLevels.ProjectOwner, canShare = true, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "reader" => Future(WorkspaceResponse(WorkspaceAccessLevels.Read, canShare = false, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "attributes" => Future(rawlsWorkspaceResponseWithAttributes)
      case "publishedreader" => Future(WorkspaceResponse(WorkspaceAccessLevels.Read, canShare = false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "publishedwriter" => Future(WorkspaceResponse(WorkspaceAccessLevels.Write, canShare = false, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case "unpublishedwriter" => Future(WorkspaceResponse(WorkspaceAccessLevels.Write, canShare = false, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
      case _ => Future.successful(WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare = true, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty))
    }
  }

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] = {
    Future.successful(Seq(WorkspaceListResponse(WorkspaceAccessLevels.ProjectOwner, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Read, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, rawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, publishedRawlsWorkspaceWithAttributes, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty),
      WorkspaceListResponse(WorkspaceAccessLevels.Owner, newWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)))
  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[Workspace] = {
    Future.successful(newWorkspace)
  }

  override def getAllLibraryPublishedWorkspaces: Future[Seq[Workspace]] = Future.successful(Seq.empty[Workspace])

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] = {
    Future.successful(WorkspaceACLUpdateResponseList(aclUpdates.map(update => WorkspaceACLUpdateResponse(update.email, update.accessLevel)), aclUpdates, aclUpdates, aclUpdates))
  }

  override def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]] = {
    Future.successful(None)
  }

  override def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit] = {
    Future.successful(())
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[Entity]] = {
    if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Workspace not found")))
    } else {
      Future.successful(Seq.empty)
    }
  }

}
