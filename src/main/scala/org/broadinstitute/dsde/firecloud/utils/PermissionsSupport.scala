package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.{FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WithAccessToken, errorReportSource}
import org.broadinstitute.dsde.rawls.model.{ErrorReport}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import org.broadinstitute.dsde.rawls.model.WorkspaceAccessLevels.WorkspaceAccessLevel
import spray.http.{HttpRequest, StatusCodes}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by dvoet on 11/5/15.
 */
trait PermissionsSupport {
  protected val rawlsDAO: RawlsDAO
//  implicit val userInfo: UserInfo
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
          ws.accessLevel >= neededLevel
        }
      } else {
        Future.successful(true)
      }
    }
  }
}

