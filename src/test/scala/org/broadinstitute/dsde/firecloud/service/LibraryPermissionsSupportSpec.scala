package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.{Workspace, WorkspaceAccessLevels, WorkspaceResponse, WorkspaceSubmissionStats}
import org.joda.time.DateTime
import org.scalatest.FreeSpecLike

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, MINUTES}

/**
 * Created by ahaessly on 3/28/17.
 */
class LibraryPermissionsSupportSpec extends BaseServiceSpec with FreeSpecLike with LibraryPermissionsSupport {
  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val rawlsDAO: RawlsDAO = rawlsDao

  val curator: UserInfo = UserInfo("curator", "curator")
  val user: UserInfo = UserInfo("user", "user")

  val dur = Duration(2, MINUTES)

  val nonRealmedRawlsWorkspace = Workspace(
    "attributes",
    "att",
    None, //realm
    "id",
    "", //bucketname
    DateTime.now(),
    DateTime.now(),
    "mb",
    Map(), //attrs
    Map(), //acls
    Map(), //realm acls
    false
  )

  val readerWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Read, canShare=false, catalog=false, nonRealmedRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val writerWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Write, canShare=false, catalog=false, nonRealmedRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val ownerWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Owner, canShare=false, catalog=false, nonRealmedRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val catalogWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.NoAccess, canShare=false, catalog=true, nonRealmedRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val readerWithShareWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Read, canShare=true, catalog=false, nonRealmedRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)
  val readerWithCatalogWorkspaceResponse = WorkspaceResponse(WorkspaceAccessLevels.Read, canShare=false, catalog=true, nonRealmedRawlsWorkspace, WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0), List.empty)

  "LibraryPermissionsSupport" - {
    "with catalog and read permissions" - {
      "should be allowed to modify workspace" in {
        assert(canModify(readerWithCatalogWorkspaceResponse))
      }
      "should be allowed to modify discoverability" in {
        assert(canModifyDiscoverability(readerWithCatalogWorkspaceResponse))
      }
      "should be allowed to modify publish" in {
        assert(Await.result(canChangePublished(readerWithCatalogWorkspaceResponse, curator), dur))
      }
      "should not be allowed to modify publish" in {
        assert(!Await.result(canChangePublished(readerWithCatalogWorkspaceResponse, user), dur))
      }
    }
    "with write permissions" - {
      "should be allowed to modify workspace" in {
        assert(canModify(writerWorkspaceResponse))
      }
      "should not be allowed to modify discoverability" in {
        assert(!canModifyDiscoverability(writerWorkspaceResponse))
      }
      "should not be allowed to modify publish" in {
        assert(!Await.result(canChangePublished(writerWorkspaceResponse, curator), dur))
      }
    }
    "with owner permissions" - {
      "should be allowed to modify workspace" in {
        assert(canModify(ownerWorkspaceResponse))
      }
      "should be allowed to modify discoverability" in {
        assert(canModifyDiscoverability(ownerWorkspaceResponse))
      }
      "should be allowed to modify publish" in {
        assert(Await.result(canChangePublished(ownerWorkspaceResponse, curator), dur))
      }
      "should not be allowed to modify publish" in {
        assert(!Await.result(canChangePublished(ownerWorkspaceResponse, user), dur))
      }
    }
    "with catalog only permissions" - {
      "should not be allowed to modify workspace" in {
        assert(!canModify(catalogWorkspaceResponse))
      }
      "should not be allowed to modify discoverability" in {
        assert(!canModifyDiscoverability(catalogWorkspaceResponse))
      }
      "should not be allowed to modify publish" in {
        assert(!Await.result(canChangePublished(catalogWorkspaceResponse, curator), dur))
      }
    }
    "with read and share permissions" - {
      "should not be allowed to modify workspace" in {
        assert(!canModify(readerWithShareWorkspaceResponse))
      }
      "should be allowed to modify discoverability" in {
        assert(canModifyDiscoverability(readerWithShareWorkspaceResponse))
      }
      "should not be allowed to modify publish" in {
        assert(!Await.result(canChangePublished(readerWithShareWorkspaceResponse, curator), dur))
      }
    }
  }
}
