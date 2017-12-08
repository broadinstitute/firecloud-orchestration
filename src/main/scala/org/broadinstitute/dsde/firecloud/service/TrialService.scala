package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SamDAO, ThurloeDAO, _}
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses.CreationStatus
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled, Terminated}
import org.broadinstitute.dsde.firecloud.model.Trial.{StatusUpdate, TrialStates, UserTrialStatus, _}
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RequestCompleteWithErrorReport, UserInfo, WithAccessToken, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impCreateProjectsResponse, impTrialProject}
import org.broadinstitute.dsde.firecloud.model.Trial.StatusUpdate.Attempt
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TrialService._
import org.broadinstitute.dsde.firecloud.trial.ProjectManager.StartCreation
import org.broadinstitute.dsde.firecloud.utils.PermissionsSupport
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException}
import org.broadinstitute.dsde.rawls.model.{RawlsBillingProjectName, RawlsUserEmail}
import spray.http.StatusCodes._
import spray.http.{OAuth2BearerToken, StatusCodes}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

// TODO: Contain userEmail in value class for stronger type safety without incurring performance penalty
object TrialService {
  sealed trait TrialServiceMessage

  case class EnableUsers(managerInfo: UserInfo, userEmails: Seq[String]) extends TrialServiceMessage
  case class DisableUsers(managerInfo: UserInfo, userEmails: Seq[String]) extends TrialServiceMessage
  case class EnrollUser(managerInfo: UserInfo) extends TrialServiceMessage
  case class TerminateUsers(managerInfo: UserInfo, userEmails: Seq[String]) extends TrialServiceMessage
  case class CreateProjects(userInfo:UserInfo, count:Int) extends TrialServiceMessage
  case class VerifyProjects(userInfo:UserInfo) extends TrialServiceMessage
  case class CountProjects(userInfo:UserInfo) extends TrialServiceMessage
  case class Report(userInfo:UserInfo) extends TrialServiceMessage

  def props(service: () => TrialService): Props = {
    Props(service())
  }

  def constructor(app: Application, projectManager: ActorRef)()(implicit executionContext: ExecutionContext) =
    new TrialService(app.samDAO, app.thurloeDAO, app.rawlsDAO, app.trialDAO, app.googleServicesDAO, projectManager)
}

// TODO: Remove loggers used for development purposes or lower their level
final class TrialService
  (val samDao: SamDAO, val thurloeDao: ThurloeDAO, val rawlsDAO: RawlsDAO, val trialDAO: TrialDAO, val googleDAO: GoogleServicesDAO, projectManager: ActorRef)
  (implicit protected val executionContext: ExecutionContext)
  extends Actor with PermissionsSupport with SprayJsonSupport with LazyLogging {

  override def receive = {
    case EnableUsers(managerInfo, userEmails) =>
      asTrialCampaignManager(enableUsers(managerInfo, userEmails))(managerInfo) pipeTo sender
    case DisableUsers(managerInfo, userEmails) =>
      asTrialCampaignManager(disableUsers(managerInfo, userEmails))(managerInfo) pipeTo sender
    case EnrollUser(userInfo) =>
      enrollUser(userInfo) pipeTo sender
    case TerminateUsers(managerInfo, userEmails) =>
      asTrialCampaignManager(terminateUsers(managerInfo, userEmails))(managerInfo) pipeTo sender
    case CreateProjects(userInfo, count) => asTrialCampaignManager {createProjects(count)}(userInfo) pipeTo sender
    case VerifyProjects(userInfo) => asTrialCampaignManager {verifyProjects}(userInfo) pipeTo sender
    case CountProjects(userInfo) => asTrialCampaignManager {countProjects}(userInfo) pipeTo sender
    case Report(userInfo) => asTrialCampaignManager {projectReport}(userInfo) pipeTo sender
    case x => throw new FireCloudException("unrecognized message: " + x.toString)
  }

  private def buildEnableUserStatus(userInfo: WorkbenchUserInfo, currentStatus: Option[UserTrialStatus]): UserTrialStatus = {
    val needsProject = currentStatus match {
      case None => true
      case Some(statusObj) => statusObj.state match {
        case Some(Disabled) => true
        case _ => false // either an invalid transition or noop
      }
    }
    if (needsProject) {
      val trialProject = trialDAO.claimProjectRecord(WorkbenchUserInfo(userInfo.userSubjectId, userInfo.userEmail))
      UserTrialStatus(userId = userInfo.userSubjectId, state = Some(Enabled), enabledDate = Instant.now, billingProjectName = Some(trialProject.name.value))
    } else {
      currentStatus.get
    }
  }

  private def enableUserPostProcessing(updateStatus: Attempt, prevStatus: Option[UserTrialStatus], newStatus: UserTrialStatus): Unit = {
    if (updateStatus != StatusUpdate.Success && newStatus.billingProjectName.isDefined) {
      logger.info(
        s"The user '${newStatus.userId}' failed to be enabled, releasing the billing project '${newStatus.billingProjectName.get}' back into the available pool.")
      trialDAO.releaseProjectRecord(RawlsBillingProjectName(newStatus.billingProjectName.get))
    }
  }

  private def enableUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    executeStateTransitions(managerInfo, userEmails, buildEnableUserStatus, enableUserPostProcessing)
  }

  private def buildDisableUserStatus(userInfo: WorkbenchUserInfo, currentStatus: Option[UserTrialStatus]): UserTrialStatus = {
    currentStatus.get.copy(state = Some(Disabled), billingProjectName = None)
  }

  private def disableUserPostProcessing(updateStatus: Attempt, prevStatus: Option[UserTrialStatus], newStatus: UserTrialStatus): Unit = {
    if (updateStatus == StatusUpdate.Success && prevStatus.isDefined) {
      prevStatus.get.billingProjectName match {
        case None => None
        case Some(name) => trialDAO.releaseProjectRecord(RawlsBillingProjectName(name))
      }
    }
  }

  private def disableUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    executeStateTransitions(managerInfo, userEmails, buildDisableUserStatus, disableUserPostProcessing)
  }

  private def buildTerminateUserStatus(userInfo: WorkbenchUserInfo, currentStatus: Option[UserTrialStatus]): UserTrialStatus = {
    require(currentStatus.nonEmpty, "Cannot terminate a user without a status")
    currentStatus.get.copy(state = Some(Terminated), terminatedDate = Instant.now)
  }

  private def terminateUsers(managerInfo: UserInfo, userEmails: Seq[String]): Future[PerRequestMessage] = {
    executeStateTransitions(managerInfo, userEmails, buildTerminateUserStatus, (_,_,_) => ())
  }

  private def requiresStateTransition(currentState: Option[UserTrialStatus], newState: UserTrialStatus): Boolean = {
    // TODO: Handle invalid initial trial status by
    //    -adding another case to Trial.StatusUpdate
    //    -using that case to return a BadRequest from the parent method (i.e. updateUserState())
    val innerState = currentState match {
      case None => None
      case Some(status) => status.state
    }
    if (innerState.isEmpty || innerState != newState.state) {
      require(newState.state.nonEmpty, "Cannot transition to an unspecified state")
      assert(newState.state.get.isAllowedFrom(innerState), s"Cannot transition from $currentState.get.state to $newState.state")
      true
    } else {
      false
    }
  }

  private def getUserSubjectId(userEmail: String): Future[String] = {
    //    // TODO: Handle unregistered users which get 404 from Sam causing adminGetUserByEmail to throw
    //    // TODO: Handle errors that may come up while querying Sam
    for {
      regInfo <- samDao.adminGetUserByEmail(RawlsUserEmail(userEmail))
    } yield regInfo.userInfo.userSubjectId
  }

  private def executeStateTransitions(managerInfo: UserInfo, userEmails: Seq[String],
                                      statusTransition: (WorkbenchUserInfo, Option[UserTrialStatus]) => UserTrialStatus,
                                      transitionPostProcessing: (Attempt, Option[UserTrialStatus], UserTrialStatus) => Unit): Future[PerRequestMessage] = {
    var results: Seq[(String,String)] = Seq()
    userEmails.foreach { userEmail =>
     val finalStatus =
      getUserSubjectId(userEmail) flatMap { subId =>
        val userInfo = WorkbenchUserInfo(subId, userEmail)
        thurloeDao.getTrialStatus(subId, managerInfo) flatMap { userTrialStatus =>
          val newStatus = statusTransition(userInfo, userTrialStatus)
          if (requiresStateTransition(userTrialStatus, newStatus)) {
            for {
              stateResponse <- updateTrialStatus(managerInfo, userInfo, newStatus)
            } yield {
              transitionPostProcessing(stateResponse, userTrialStatus, newStatus)
              StatusUpdate.toName(stateResponse)
            }
          } else {
            logger.warn(
              s"The user '${userInfo.userEmail}' is already in the trial state of '$newStatus.newState'. " +
                s"No further action will be taken.")
            Future.successful(StatusUpdate.toName(StatusUpdate.NoChangeRequired))
          }
        }
      }
      // Use await here so that multiple Futures don't have a conflict when claiming a billing project
      val result = (Await.result(finalStatus, scala.concurrent.duration.Duration.Inf))
      results = results ++ Seq((result, userEmail))
    }
    val sorted = results.groupBy(_._1).map { case (k,v) => (k,v.map(_._2))}
    Future.successful(RequestComplete(sorted))
  }

  private def updateTrialStatus(managerInfo: UserInfo,
                                userInfo: WorkbenchUserInfo,
                                updatedTrialStatus: UserTrialStatus): Future[StatusUpdate.Attempt] = {

    // TODO: Test the logic below by adding a mock ThurloeDAO whose saveTrialStatus() returns Failure
    thurloeDao.saveTrialStatus(userInfo.userSubjectId, managerInfo, updatedTrialStatus) map {
      case Success(_) =>
        StatusUpdate.Success
      case Failure(ex) =>
        StatusUpdate.ServerError(ex.getMessage)
    }
  }

  private def enrollUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    // get user's trial status, then check the current state
    thurloeDao.getTrialStatus(userInfo.id, userInfo) flatMap { userTrialStatus =>
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
              thurloeDao.saveTrialStatus(userInfo.id, userInfo, enrolledStatus) map { _ =>
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

  private def createProjects(count: Int): Future[PerRequestMessage] = {
    implicit val timeout:Timeout = 1.minute // timeout to get a response from projectManager
    val create = projectManager ? StartCreation(count)
    create.map {
      case c:CreateProjectsResponse if c.success => RequestComplete(StatusCodes.Accepted, c)
      case c:CreateProjectsResponse if !c.success => RequestComplete(BadRequest, c)
      case _ => RequestComplete(InternalServerError)
    }
  }

  private def verifyProjects: Future[PerRequestMessage] = {

    val saToken:WithAccessToken = AccessToken(OAuth2BearerToken(googleDAO.getTrialBillingManagerAccessToken))
    rawlsDAO.getProjects(saToken) map { projects =>

      val projectStatuses:Map[RawlsBillingProjectName, CreationStatus] = projects.map { proj =>
        proj.projectName -> proj.creationStatus
      }.toMap

      // get unverified projects from the pool
      val unverified = trialDAO.listUnverifiedProjects

      unverified.foreach { unv =>
        // get status from the rawls map
        projectStatuses.get(unv.name) match {
          case Some(CreationStatuses.Creating) => // noop
          case Some(CreationStatuses.Error) =>
            logger.warn(s"project ${unv.name.value} errored, so we have to give up on it.")
            trialDAO.setProjectRecordVerified(unv.name, verified=true, status = CreationStatuses.Error)
          case Some(CreationStatuses.Ready) =>
            trialDAO.setProjectRecordVerified(unv.name, verified=true, status = CreationStatuses.Ready)
          case None =>
            logger.warn(s"project ${unv.name.value} exists in pool but not found via Rawls!")
        }
      }

      RequestComplete(OK, trialDAO.countProjects)
    }
  }

  private def countProjects: Future[PerRequestMessage] =
    Future(RequestComplete(OK, trialDAO.countProjects))

  private def projectReport: Future[PerRequestMessage] =
    Future(RequestComplete(OK, trialDAO.projectReport))

}
