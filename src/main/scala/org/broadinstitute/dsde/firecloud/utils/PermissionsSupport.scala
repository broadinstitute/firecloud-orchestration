package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SamDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import org.broadinstitute.dsde.firecloud.{FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, WorkspaceAccessLevels}
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels.WorkspaceAccessLevel
import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by dvoet on 11/5/15.
 */
trait PermissionsSupport {
  protected val rawlsDAO: RawlsDAO
  protected val samDao: SamDAO
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

  def asPermitted(ns: String, name: String, lvl: WorkspaceAccessLevel, userInfo: UserInfo)(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    hasAccessOrAdmin(ns, name, lvl, userInfo) flatMap { isPermitted =>
      if (isPermitted) op else Future.failed(new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.Forbidden, s"You must be an admin or have at least ${lvl.toString} access.")))
    }
  }

  private def hasAccessOrAdmin(workspaceNamespace: String, workspaceName: String, neededLevel: WorkspaceAccessLevel, userInfo: UserInfo): Future[Boolean] = {
    tryIsAdmin(userInfo) flatMap { isadmin =>
      if (!isadmin) {
        rawlsDAO.getWorkspace(workspaceNamespace, workspaceName)(userInfo.asInstanceOf[WithAccessToken]) map { ws =>
          ws.accessLevel match {
            case Some(accessLevel) => accessLevel >= neededLevel
            case None => false
          }
        }
      } else {
        Future.successful(true)
      }
    }
  }

  def asGroupMember(group: String)(op: => Future[PerRequestMessage])(implicit userInfo: UserInfo): Future[PerRequestMessage] = {
    tryIsGroupMember(userInfo, group) flatMap { isGroupMember =>
      if (isGroupMember) op else Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be in the appropriate group.")))
    }
  }

  def tryIsGroupMember(userInfo: UserInfo, group: String): Future[Boolean] = {
    samDao.isGroupMember(WorkbenchGroupName(group), userInfo) recoverWith {
      case t: Throwable => throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Unable to query for group membership status."))
    }
  }
}

