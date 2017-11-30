package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TrialService._
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import spray.http.OAuth2BearerToken
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

  // TODO: implement fully! Check that the user does not already have a state, before overwriting.
  // this method exists solely for developer-testing purposes right now.
  private def enableUsers(userInfo: UserInfo,
                          users: Seq[String]): Future[PerRequestMessage] = {

    // For each user in the list, query SAM to get their subjectIds
    val registrationInfoFutures = users.map(user => samDao.adminGetUserByEmail(RawlsUserEmail(user)))
    val registrationInfosFuture = Future.sequence(registrationInfoFutures)

    val subjectIdsFuture = for {
      registrationInfos <- registrationInfosFuture
      subjectIds = registrationInfos.map(_.userInfo.userSubjectId)
    } yield subjectIds

    //return subjectIdsFuture.map(RequestComplete(OK, _))

    // TODO: separate the input list into registered/unregistered users
    // (and maybe a third category of users that had an error during lookup)

    // TODO: loop over all registered users
    Seq("these", "are", "fake", "registered", "users") foreach { user =>

      // TODO: make a fake userinfo as the target user. Pull the info from the "registeredUsers" lookup
      val sudoUserInfo: UserInfo = UserInfo("target user's email", OAuth2BearerToken("unused-can-be-anything"), 12345, "target-users-subject-id")

      // TODO: get the user's trial status from Thurloe
      val userProfile = thurloeDao.getTrialStatus(sudoUserInfo)

      // TODO: read the user's trial status; enforce business logic
      // e.g. if enabling user, the user must currently be disabled or have no trial state at all
      // if business logic validation fails, add to an "errors" list that can be returned to the caller

      // TODO: generate a new TrialStatus
      // build the state that we want to persist to indicate the user is enabled
      val now = Instant.now
      val zero = Instant.ofEpochMilli(0)
      val enabledStatus = UserTrialStatus(userInfo.id, Some(TrialStates.Enabled), now, zero, zero, zero)

      // TODO: save updates to user's trial status
      thurloeDao.saveTrialStatus(sudoUserInfo, enabledStatus)
    }

    // TODO: return something helpful to the caller
    Future(RequestComplete(EnhanceYourCalm, Map(
      "enabled" -> Seq("foo", "bar"),
      "unregistered" -> Seq("baz", "qux"),
      "errors" -> Seq("can we return", "error messages here", "in a useful way?")
    )))
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

