package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.{FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WithAccessToken, errorReportSource}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, WorkspaceAccessLevels, WorkspaceResponse}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import spray.http.{HttpRequest, StatusCodes}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by dvoet on 11/5/15.
 */
trait PermissionsSupport {
  protected val rawlsDAO: RawlsDAO
  implicit protected val executionContext: ExecutionContext

  def tryIsAdmin(userInfo: UserInfo): Future[Boolean] = {
    rawlsDAO.isAdmin(userInfo) recoverWith { case t => throw new FireCloudException("Unable to query for admin status.", t) }
  }

  def asAdmin(op: => Future[PerRequestMessage])(implicit userInfo: UserInfo): Future[PerRequestMessage] = {
    tryIsAdmin(userInfo) flatMap { isAdmin =>
      if (isAdmin) op else Future.failed(new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.Forbidden, "You must be an admin.")))
    }
  }

  def tryIsCurator(userInfo: UserInfo): Future[Boolean] = {
    rawlsDAO.isLibraryCurator(userInfo) recoverWith { case t => throw new FireCloudException("Unable to query for library curator status.", t) }
  }

  def asCurator(op: => Future[PerRequestMessage])(implicit userInfo: UserInfo): Future[PerRequestMessage] = {
    tryIsCurator(userInfo) flatMap { isCurator =>
      if (isCurator) op else Future.failed(new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.Forbidden, "You must be a library curator.")))
    }
  }

  def hasAccessOrAdmin(workspaceNamespace: String, workspaceName: String, neededLevel: WorkspaceAccessLevels.WorkspaceAccessLevel, userInfo: UserInfo): Future[Boolean] = {
    tryIsAdmin(userInfo) flatMap { isadmin =>
      if (!isadmin) {
        rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo.asInstanceOf[WithAccessToken]) map { ws =>
          ws.accessLevel >= neededLevel
        }
      } else {
        Future.successful(true)
      }
    }
  }

  def hasAccessOrAdminFromWorkspace(workspaceResponse: WorkspaceResponse, neededLevel: WorkspaceAccessLevels.WorkspaceAccessLevel, userInfo: UserInfo): Future[Boolean] = {
    val wsaccess = workspaceResponse.accessLevel >= neededLevel
    if (!wsaccess)
      tryIsAdmin(userInfo)
    else
      Future.successful(wsaccess)
  }

  def hasAccessOrCurator(workspaceNamespace: String, workspaceName: String, neededLevel: WorkspaceAccessLevels.WorkspaceAccessLevel, userInfo: UserInfo): Future[Boolean] = {
    tryIsCurator(userInfo) map { iscurator =>
      if (!iscurator) {
        rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo.asInstanceOf[WithAccessToken]) map { ws =>
          ws.accessLevel >= neededLevel
        }
      }
      true
    }
  }

  def hasAccessOrCurator(workspaceResponse: WorkspaceResponse, neededLevel: WorkspaceAccessLevels.WorkspaceAccessLevel, userInfo: UserInfo): Future[Boolean] = {
    val wsaccess = workspaceResponse.accessLevel >= neededLevel
    if (!wsaccess)
      tryIsCurator(userInfo)
    else
      Future.successful(wsaccess)
  }

}
