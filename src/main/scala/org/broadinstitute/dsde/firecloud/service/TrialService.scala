package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled, Terminated, TrialState}
import org.broadinstitute.dsde.firecloud.model.Trial.{StatusUpdate, TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TrialService._
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

// TODO: Contain userEmail in value class for stronger type safety without incurring performance penalty
object TrialService {
  sealed trait TrialServiceMessage
  case class EnableUsers(managerInfo: UserInfo, userEmails: Seq[String]) extends TrialServiceMessage
  case class DisableUsers(managerInfo: UserInfo, userEmails: Seq[String]) extends TrialServiceMessage
  case class EnrollUser(managerInfo: UserInfo) extends TrialServiceMessage
  case class TerminateUsers(managerInfo: UserInfo, userEmails: Seq[String]) extends TrialServiceMessage

  def props(service: () => TrialService): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new TrialService(app.samDAO, app.thurloeDAO)
}

// TODO: Remove loggers used for development purposes or lower their level
final class TrialService
  (val samDao: SamDAO, val thurloeDao: ThurloeDAO)
  (implicit protected val executionContext: ExecutionContext)
  extends Actor with LazyLogging with SprayJsonSupport {

  override def receive = {
    case EnableUsers(managerInfo, userEmails) => enableUsers(managerInfo, userEmails) pipeTo sender
    case DisableUsers(managerInfo, userEmails) => disableUsers(managerInfo, userEmails) pipeTo sender
    case EnrollUser(managerInfo) => enrollUser(managerInfo) pipeTo sender
    case TerminateUsers(managerInfo, userEmails) => terminateUsers(managerInfo, userEmails) pipeTo sender
  }

  private def enableUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    // TODO: Handle multiple users
    require(userEmails.size == 1, "For the time being, we can only enable one user at a time.")

    val userEmail = userEmails.head

    // Define what to overwrite in user's status
    val statusTransition: UserTrialStatus => UserTrialStatus =
      status => status.copy(state = Some(Enabled), enabledDate = Instant.now)

    updateUserState(managerInfo, userEmail, statusTransition)
  }

  private def disableUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    // TODO: Handle multiple users
    require(userEmails.size == 1, "For the time being, we can only disable one user at a time.")

    val userEmail = userEmails.head

    // Define what to overwrite in user's status
    val statusTransition: UserTrialStatus => UserTrialStatus =
      status => status.copy(state = Some(Disabled))

    updateUserState(managerInfo, userEmail, statusTransition)
  }

  private def terminateUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    // TODO: Handle multiple users
    require(userEmails.size == 1, "For the time being, we can only terminate one user at a time.")

    val userEmail = userEmails.head

    // Define what to overwrite in user's status
    val statusTransition: UserTrialStatus => UserTrialStatus =
      status => status.copy(state = Some(Terminated), terminatedDate = Instant.now)

    updateUserState(managerInfo, userEmail, statusTransition)
  }

  private def updateUserState(managerInfo: UserInfo,
                              userEmail: String,
                              statusTransition: UserTrialStatus => UserTrialStatus): Future[PerRequestMessage] = {
    // TODO: Handle unregistered users which get 404 from Sam causing adminGetUserByEmail to throw
    // TODO: Handle errors that may come up while querying Sam
    for {
      regInfo <- samDao.adminGetUserByEmail(RawlsUserEmail(userEmail))
      userInfo = WorkbenchUserInfo(regInfo.userInfo.userSubjectId, regInfo.userInfo.userEmail)
      result <- updateTrialStatus(managerInfo, userInfo, statusTransition)
    } yield result match {
      case StatusUpdate.Success => RequestComplete(NoContent)
      case StatusUpdate.Failure => RequestComplete(InternalServerError)
    }
  }

  private def updateTrialStatus(managerInfo: UserInfo,
                                userInfo: WorkbenchUserInfo,
                                statusTransition: UserTrialStatus => UserTrialStatus): Future[StatusUpdate.Attempt] = {
    // Create hybrid UserInfo with the managerInfo's credentials and users' subjectIds
    val sudoUserInfo = managerInfo.copy(id = userInfo.userSubjectId)

    // Use the hybrid UserInfo while querying Thurloe
    thurloeDao.getTrialStatus(sudoUserInfo) map {
      case Some(currentStatus) =>
        val currentState = currentStatus.state
        val newStatus = statusTransition(currentStatus)
        val newState = newStatus.state

        if (currentState == newState) {
          logger.warn(
            s"The user '${userInfo.userEmail}' is already in the trial state of '$newState'. " +
            s"No further action will be taken.")

          StatusUpdate.Success
        } else {
          logger.warn(s"Current trial state of the user '${userInfo.userEmail}' is '$currentState'")
          logger.warn("Checking if enabling is allowed from that state...")

          require(newState.nonEmpty, "Cannot transition to an unspecified state")

          // TODO Handle invalid initial trial status by
          //    -adding another case to Trial.StatusUpdate
          //    -using that case to return a BadRequest from the parent method (i.e. updateUserState())
          assert(newState.get.isAllowedFrom(currentState),
            s"Cannot transition from $currentState to $newState")

          // Save updates to user's trial status
          thurloeDao.saveTrialStatus(sudoUserInfo, newStatus)
          logger.warn(s"Updated profile saved as $newState; we are done!")

          StatusUpdate.Success
        }
      // TODO: Handle case where TrialStatusOpt is None, i.e., if the user profile doesn't exist
      // in Thurloe, create one and set to the new state along with the other tags
      case None =>
        logger.warn(s"Trial status could not be found for ${userInfo.userEmail}")

        StatusUpdate.Failure
    }
  }

  private def enrollUser(userInfo:UserInfo): Future[PerRequestMessage] = {
    // get user's trial status, then check the current state
    thurloeDao.getTrialStatus(userInfo) flatMap { userTrialStatus =>
      userTrialStatus match {
        // can't determine the user's trial status; don't enroll
        case None => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial."))
        case Some(status) =>
          status.state match {
            // user already enrolled; don't re-enroll
            case Some(TrialStates.Enrolled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are already enrolled in a free trial."))
            // user enabled (eligible) for trial, enroll!
            case Some(TrialStates.Enabled) => {
              // build the new state that we want to persist to indicate the user is enrolled
              val now = Instant.now
              val expirationDate = now.plus(FireCloudConfig.Trial.durationDays, ChronoUnit.DAYS)
              val enrolledStatus = status.copy(
                state = Some(TrialStates.Enrolled),
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
}

