package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.model.{UserInfo, errorReportSource}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import org.broadinstitute.dsde.firecloud.utils.PermissionsSupport
import org.broadinstitute.dsde.rawls.model.{ErrorReport, WorkspaceAccessLevels, WorkspaceResponse}
import spray.http.StatusCodes

import scala.concurrent.Future

/**
 * Created by ahaessly on 3/21/17.
 */
trait LibraryPermissionsSupport extends PermissionsSupport {

  protected def hasCatalog(workspaceResponse: WorkspaceResponse): Boolean = {
    workspaceResponse.catalog && workspaceResponse.accessLevel >= WorkspaceAccessLevels.Read
  }

  protected def canChangePublished(workspaceResponse: WorkspaceResponse, userInfo: UserInfo): Future[Boolean] = {
    tryIsCurator(userInfo) map { iscurator =>
      // must be curator and then be owner or have catalog with at least read for the workspace
      iscurator &&
        (workspaceResponse.accessLevel >= WorkspaceAccessLevels.Owner || hasCatalog(workspaceResponse))
    }
  }

  def withChangePublishedPermissions(workspaceResponse: WorkspaceResponse, userInfo: UserInfo)(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    canChangePublished(workspaceResponse, userInfo) flatMap { allowed =>
      if (allowed)
        op
      else
        Future.failed(new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.Forbidden, s"You must be a curator and either be an owner or have catalog with read+.")))
    }
  }

  protected def canModify(workspaceResponse: WorkspaceResponse): Boolean = {
    workspaceResponse.accessLevel >= WorkspaceAccessLevels.Write ||
      hasCatalog(workspaceResponse)
  }

  def withModifyPermissions(workspaceResponse: WorkspaceResponse)(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    if (canModify(workspaceResponse))
      op
    else
      Future.failed(new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.Forbidden, s"You must have write+ or catalog with read permissions.")))
  }

  protected def canModifyDiscoverability(workspaceResponse: WorkspaceResponse): Boolean = {
    workspaceResponse.accessLevel >= WorkspaceAccessLevels.Owner ||
      workspaceResponse.canShare ||
      hasCatalog(workspaceResponse)
  }

  def withDiscoverabilityModifyPermissions(workspaceResponse: WorkspaceResponse)(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    if (canModifyDiscoverability(workspaceResponse))
      op
    else
      Future.failed(new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.Forbidden, s"You must be an owner or have catalog or share permissions.")))
  }

}
