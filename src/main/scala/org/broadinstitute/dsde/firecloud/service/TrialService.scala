package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.PermissionsSupport
import org.broadinstitute.dsde.firecloud.Application

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object TrialService {

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new TrialService(app.samDAO, app.thurloeDAO, app.rawlsDAO, app.googleServicesDAO)

}

final class TrialService(val samDao: SamDAO, val thurloeDao: ThurloeDAO, val rawlsDAO: RawlsDAO, val googleDAO: GoogleServicesDAO)(implicit protected val executionContext: ExecutionContext)
  extends PermissionsSupport with LazyLogging with SprayJsonSupport {

  def FinalizeUser(userInfo: UserInfo) = finalizeUser(userInfo)

  private def finalizeUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    import org.broadinstitute.dsde.firecloud.model.Project.TrialStates._

    // Get user's trial status, check and update the current state if it's a valid transition
    // NB: We are being lenient and are not complaining when a user was already 'finalized' previously
    thurloeDao.getTrialStatus(userInfo.id, userInfo) flatMap { status =>
      val state = status.state
      (Finalized.isAllowedFrom(state), state.contains(Terminated)) match {
        case (true, true) =>
          thurloeDao.saveTrialStatus(userInfo.id, userInfo, status.copy(state = Some(Finalized))) flatMap {
            case Success(_) => Future(RequestComplete(NoContent))
            case Failure(ex) => Future(RequestComplete(InternalServerError, ex.getMessage))
          }
        case (true, false) => Future(RequestComplete(NoContent))
        case _ => Future(RequestCompleteWithErrorReport(BadRequest, "Your free trial should have been terminated first."))
      }
    }
  }

}
