package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TrialService.{EnableUser, EnrollUser, TerminateUser}
import spray.http.StatusCodes._

import scala.concurrent.{ExecutionContext, Future}

object TrialService {
  sealed trait TrialServiceMessage
  case class EnableUser(userInfo:UserInfo) extends TrialServiceMessage
  case class EnrollUser(userInfo:UserInfo) extends TrialServiceMessage
  case class TerminateUser(userInfo:UserInfo) extends TrialServiceMessage

  def props(service: () => TrialService): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new TrialService(app.samDAO, app.thurloeDAO)
}

class TrialService
  (val samDao: SamDAO, val thurloeDao: ThurloeDAO)
  (implicit protected val executionContext: ExecutionContext)
  extends Actor with LazyLogging {

  override def receive = {
    case EnableUser(userInfo) => enableUser(userInfo) pipeTo sender
    case EnrollUser(userInfo) => enrollUser(userInfo) pipeTo sender
    case TerminateUser(userInfo) => terminateUser(userInfo) pipeTo sender
  }

  // TODO: implement fully! Check that the user does not already have a state, before overwriting.
  // this method exists solely for developer-testing purposes right now.
  private def enableUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    // build the state that we want to persist to indicate the user is enabled
    val now = Instant.now
    val zero = Instant.ofEpochMilli(0)
    val enabledStatus = UserTrialStatus(userInfo.id, Some(TrialStates.Enabled), now, zero, zero, zero)
    thurloeDao.saveTrialStatus(userInfo, enabledStatus) map { _ =>
      RequestComplete(spray.http.StatusCodes.OK)
    }
  }

  private def enrollUser(userInfo:UserInfo): Future[PerRequestMessage] = {
    // get user's trial status, then check the current state
    thurloeDao.getTrialStatus(userInfo) flatMap { userTrialStatus =>
      userTrialStatus match {
        // can't determine the user's trial status; don't enroll
        case None => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial."))
        case Some(status) =>
          status.currentState match {
            // user already enrolled; don't re-enroll
            case Some(TrialStates.Enrolled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are already enrolled in a free trial."))
            // user enabled (eligible) for trial, enroll!
            case Some(TrialStates.Enabled) => {
              // build the new state that we want to persist to indicate the user is enrolled
              val now = Instant.now
              val expirationDate = now.plus(FireCloudConfig.Trial.durationDays, ChronoUnit.DAYS)
              val enrolledStatus = status.copy(
                currentState = Some(TrialStates.Enrolled),
                enrolledDate = now,
                expirationDate = expirationDate
              )
              thurloeDao.saveTrialStatus(userInfo, enrolledStatus) map { _ =>
                // TODO: add user to free-trial billing project / create said project if necessary
                RequestComplete(NoContent)
              }
            }
            // user in some other state; don't enroll
            case _ => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial."))
          }
      }
    }
  }

  // TODO: implement
  private def terminateUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    Future(RequestCompleteWithErrorReport(NotImplemented, "not implemented"))
  }

}

