package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.{FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.model.{ErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.PerRequestMessage
import spray.http.StatusCodes

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by dvoet on 11/5/15.
 */
trait RoleSupport {
  protected val rawlsDAO: RawlsDAO
  protected val userInfo: UserInfo
  implicit protected val executionContext: ExecutionContext

  def tryIsCurator(userInfo: UserInfo): Future[Boolean] = {
    rawlsDAO.isLibraryCurator(userInfo) recoverWith { case t => throw new FireCloudException("Unable to query for library curator status.", t) }
  }

  def asCurator(op: => Future[PerRequestMessage]): Future[PerRequestMessage] = {
    tryIsCurator(userInfo) flatMap { isCurator =>
      if (isCurator) op else Future.failed(new FireCloudExceptionWithErrorReport(errorReport = ErrorReport(StatusCodes.Forbidden, "You must be a library curator.")))
    }
  }
}
