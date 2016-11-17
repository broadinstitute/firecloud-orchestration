package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.AttributeUpdateOperation
import org.broadinstitute.dsde.firecloud.model._
import spray.http.StatusCodes

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
  * Created by davidan on 9/28/16.
  *
  * Not currently used; serves as example code only
  *
  */
class MockRawlsDAO  extends RawlsDAO {

  override def isAdmin(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def isLibraryCurator(userInfo: UserInfo): Future[Boolean] = Future(true)

  override def getWorkspace(ns: String, name: String)(implicit userInfo: UserInfo): Future[RawlsWorkspaceResponse] = {
    ns match {
      case "projectowner" => Future(RawlsWorkspaceResponse("PROJECT_OWNER", newWorkspace, SubmissionStats(runningSubmissionsCount = 0), List.empty))
      case "reader" => Future(RawlsWorkspaceResponse("READER", newWorkspace, SubmissionStats(runningSubmissionsCount = 0), List.empty))
      case _ => Future(RawlsWorkspaceResponse("OWNER", newWorkspace, SubmissionStats(runningSubmissionsCount = 0), List.empty))
    }

  }

  override def patchWorkspaceAttributes(ns: String, name: String, attributes: Seq[AttributeUpdateOperation])(implicit userInfo: UserInfo): Future[RawlsWorkspace] = {
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

  override def patchWorkspaceACL(ns: String, name: String, aclUpdates: Seq[WorkspaceACLUpdate])(implicit userInfo: UserInfo): Future[Seq[WorkspaceACLUpdate]] = {
    Future(aclUpdates)
  }

  override def fetchAllEntitiesOfType(workspaceNamespace: String, workspaceName: String, entityType: String)(implicit userInfo: UserInfo): Future[Seq[RawlsEntity]] = {
    if (workspaceName == "invalid") {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "not found")))
    } else {
      Future.successful(Seq.empty)
    }
  }
}
