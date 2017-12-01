package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.dataaccess.{SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.Enabled
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo, WorkbenchUserInfo}
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

  private def enableUsers(userInfo: UserInfo,
                          users: Seq[String]): Future[PerRequestMessage] = {

    // For each user in the list, query SAM to get their subjectIds
    // TODO: Handle unregistered users which get 404 from Sam causing adminGetUserByEmail to throw
    // TODO: Handle errors that may come up while querying Sam
    val registrationInfoFutures = users.map(user => samDao.adminGetUserByEmail(RawlsUserEmail(user)))
    val registrationInfosFuture = Future.sequence(registrationInfoFutures)

    val workbenchUserInfosFuture: Future[Seq[WorkbenchUserInfo]] = for {
      registrationInfos <- registrationInfosFuture
      workbenchUserInfos = registrationInfos.map {
        regInfo => WorkbenchUserInfo(regInfo.userInfo.userSubjectId, regInfo.userInfo.userEmail)
      }
    } yield workbenchUserInfos

    workbenchUserInfosFuture.map { workbenchUserInfos =>
      workbenchUserInfos.foreach { info =>
        // Create a UserInfo as the target user, faking the fields we don't care about
        val sudoUserInfo = UserInfo(info.userEmail, OAuth2BearerToken("unused-can-be-anything"), 12345, info.userSubjectId)

        // Get the user's trial status from Thurloe
        val userProfile = thurloeDao.getTrialStatus(sudoUserInfo)

        userProfile.foreach {
          trialStatusOpt =>
            // TODO Handle case where TrialStatus is None
            val trialStatus = trialStatusOpt.get
            println(s"Current trial status of ${info.userEmail} is $trialStatus")
            println("Checking if enabling is allowed from that state...")

            // TODO Handle invalid trial status
            assert(Enabled.isAllowedFrom(trialStatus.currentState))

            // Generate and persist a new TrialStatus to indicate the user is enabled
            val now = Instant.now
            val zero = Instant.ofEpochMilli(0)
            val enabledStatus = UserTrialStatus(userInfo.id, Some(TrialStates.Enabled), now, zero, zero, zero)

            // Save updates to user's trial status
            thurloeDao.saveTrialStatus(sudoUserInfo, enabledStatus)
        }
      }

      RequestComplete(OK, workbenchUserInfos.map(_.userSubjectId))
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

