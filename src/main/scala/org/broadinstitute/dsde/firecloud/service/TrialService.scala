package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Enabled, TrialState}
import org.broadinstitute.dsde.firecloud.model.Trial.{StatusUpdate, TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TrialService._
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object TrialService {
  sealed trait TrialServiceMessage
  case class EnableUsers(userInfo:UserInfo, users: Seq[String]) extends TrialServiceMessage
  case class DisableUsers(userInfo:UserInfo, users: Seq[String]) extends TrialServiceMessage
  case class EnrollUser(userInfo:UserInfo) extends TrialServiceMessage
  case class TerminateUsers(userInfo:UserInfo, users: Seq[String]) extends TrialServiceMessage

  def props(service: () => TrialService): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new TrialService(app.samDAO, app.thurloeDAO)
}

class TrialService
  (val samDao: SamDAO, val thurloeDao: ThurloeDAO)
  (implicit protected val executionContext: ExecutionContext)
  extends Actor with LazyLogging with SprayJsonSupport {

  override def receive = {
    case EnableUsers(userInfo, users) => enableUsers(userInfo, users) pipeTo sender
    case DisableUsers(userInfo, users) => disableUsers(userInfo, users) pipeTo sender
    case EnrollUser(userInfo) => enrollUser(userInfo) pipeTo sender
    case TerminateUsers(userInfo, users) => terminateUsers(userInfo, users) pipeTo sender
  }

  private def enableUsers(managerInfo: UserInfo,
                          users: Seq[String]): Future[PerRequestMessage] = {
    // TODO: Handle multiple users
    require(users.size == 1, "For the time being, we can only enable one user at a time.")

    val user = users.head

    enableUser(managerInfo, user)
  }

  private def enableUser(managerInfo: UserInfo,
                         user: String): Future[PerRequestMessage] = {
    // TODO: Handle unregistered users which get 404 from Sam causing adminGetUserByEmail to throw
    // TODO: Handle errors that may come up while querying Sam
    for {
      regInfo <- samDao.adminGetUserByEmail(RawlsUserEmail(user))
      workbenchUserInfo = WorkbenchUserInfo(regInfo.userInfo.userSubjectId, regInfo.userInfo.userEmail)
      result <- updateTrialStatus(managerInfo, workbenchUserInfo, TrialStates.Enabled)
    } yield result match {
      case StatusUpdate.Success => RequestComplete(NoContent)
      case StatusUpdate.Failure => RequestComplete(InternalServerError)
    }
  }

  private def updateTrialStatus(managerInfo: UserInfo,
                                userInfo: WorkbenchUserInfo,
                                newState: TrialState): Future[StatusUpdate.Attempt] = {
    // Create hybrid UserInfo with the managerInfo's credentials and users' subjectIds
    val sudoUserInfo = managerInfo.copy(id = userInfo.userSubjectId)

    // Use the hybrid UserInfo while querying Thurloe
    thurloeDao.getTrialStatus(sudoUserInfo) map {
      case Some(trialStatus) =>
        val currentState = trialStatus.currentState

        if (currentState.contains(newState)) {
          logger.warn(
            s"The user '${userInfo.userEmail}' is already in the trial state of '$newState'. " +
            s"No further action will be taken.")

          StatusUpdate.Success
        } else {
          logger.warn(s"Current trial state of the user '${userInfo.userEmail}' is '$currentState'")
          logger.warn("Checking if enabling is allowed from that state...")
          // TODO Handle invalid initial trial status
          // TODO: Should we consider it a success and do nothing if the user's initial status
          // is already Enabled?
          assert(Enabled.isAllowedFrom(currentState),
            s"Cannot transition from $currentState to $newState")

          // Generate and persist a new TrialStatus to indicate the user is enabled
          val now = Instant.now
          val zero = Instant.ofEpochMilli(0)
          val newStatus = UserTrialStatus(managerInfo.id, Some(newState), now, zero, zero, zero)

          // Save updates to user's trial status
          thurloeDao.saveTrialStatus(sudoUserInfo, newStatus)
          logger.warn("Updated profile saved; we are done!")

          StatusUpdate.Success
        }
      // TODO: Handle case where TrialStatusOpt is None, i.e., if the user profile doesn't exist
      // in Thurloe, create one and set to Enabled along with the other tags
      case None =>
        logger.warn(s"Trial status could not be found for ${userInfo.userEmail}")

        StatusUpdate.Failure
    }
  }

  // TODO: implement
  private def disableUsers(userInfo: UserInfo,
                           users: Seq[String]): Future[PerRequestMessage] = ???

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
  private def terminateUsers(userInfo: UserInfo, users: Seq[String]): Future[PerRequestMessage] = {
    Future(RequestCompleteWithErrorReport(NotImplemented, "not implemented"))
  }

}

