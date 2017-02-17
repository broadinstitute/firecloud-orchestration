package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model._
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

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def isDbGapAuthorized(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def getGroupsForUser(implicit userToken: WithAccessToken): Future[Seq[String]] = {
    Future(Seq("TestUserGroup"))
  }

  override def getBucketUsage(ns: String, name: String)(implicit userInfo: WithAccessToken): Future[RawlsBucketUsageResponse] = {
    Future(RawlsBucketUsageResponse(BigInt("256000000000")))
  }

  private val rawlsWorkspaceWithAttributes = RawlsWorkspace(
    "id",
    "attributes",
    "att",
    Option(false),
    "ansingh",
    "date",
    Some("date"),
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
    "",
    Map("" -> Map("" -> "")),
    Some(Map("" -> ""))
  )

  val rawlsWorkspaceResponseWithAttributes = RawlsWorkspaceResponse("", Some(false), rawlsWorkspaceWithAttributes, SubmissionStats(runningSubmissionsCount = 0), List.empty)


  override def getWorkspace(ns: String, name: String)(implicit userToken: WithAccessToken): Future[RawlsWorkspaceResponse] = {
    ns match {
      case "projectowner" => Future(RawlsWorkspaceResponse("PROJECT_OWNER", Some(true), newWorkspace, SubmissionStats(runningSubmissionsCount = 0), List.empty))
      case "reader" => Future(RawlsWorkspaceResponse("READER", Some(false), newWorkspace, SubmissionStats(runningSubmissionsCount = 0), List.empty))
      case "attributes" => Future(rawlsWorkspaceResponseWithAttributes)
      case _ => Future(RawlsWorkspaceResponse("OWNER", Some(true), newWorkspace, SubmissionStats(runningSubmissionsCount = 0), List.empty))
    }

  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userToken: WithAccessToken): Future[RawlsWorkspace] = {
    Future.successful(newWorkspace)
  }

  private def newWorkspace: RawlsWorkspace = {
    new RawlsWorkspace(
      workspaceId = "workspaceId",
      namespace = "namespace",
      name = "name",
      isLocked = Some(true),
      createdBy = "createdBy",
      createdDate = "createdDate",
      lastModified = None,
      attributes = Map(),
      bucketName = "bucketName",
      accessLevels = Map(),
      realm = None)
  }

  override def getAllLibraryPublishedWorkspaces: Future[Seq[RawlsWorkspace]] = Future(Seq.empty[RawlsWorkspace])

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate], inviteUsersNotFound: Boolean)(implicit userToken: WithAccessToken): Future[WorkspaceACLUpdateResponseList] = {
    Future(WorkspaceACLUpdateResponseList(aclUpdates.map(update => WorkspaceACLUpdateResponse(update.email, update.accessLevel)), aclUpdates, aclUpdates, aclUpdates))
  }

  override def getRefreshTokenStatus(userInfo: UserInfo): Future[Option[DateTime]] = {
    Future(None)
  }

  override def saveRefreshToken(userInfo: UserInfo, refreshToken: String): Future[Unit] = {
    Future(())
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[RawlsEntity]] = {
    if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "not found")))
    } else {
      Future.successful(Seq.empty)
    }
  }
}
