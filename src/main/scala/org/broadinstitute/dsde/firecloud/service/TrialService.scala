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
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TrialService._
import org.broadinstitute.dsde.firecloud.trial.ProjectManager.StartCreation
import org.broadinstitute.dsde.firecloud.utils.PermissionsSupport
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{RawlsBillingProjectName, RawlsUserEmail}
import spray.http.StatusCodes._
import spray.http.{OAuth2BearerToken, StatusCodes}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRawlsBillingProjectMember

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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
  (val samDao: SamDAO, val thurloeDao: ThurloeDAO, val rawlsDAO: RawlsDAO,
   val trialDAO: TrialDAO, val googleDAO: GoogleServicesDAO, projectManager: ActorRef)
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
      case StatusUpdate.ServerError(msg) => RequestComplete(InternalServerError, msg)
    }
  }

  // TODO: Try to refactor this method that got unwieldy
  private def updateTrialStatus(managerInfo: UserInfo,
                                userInfo: WorkbenchUserInfo,
                                statusTransition: UserTrialStatus => UserTrialStatus): Future[StatusUpdate.Attempt] = {
    // Create hybrid UserInfo with the managerInfo's credentials and users' subjectIds
    val sudoUserInfo = managerInfo.copy(id = userInfo.userSubjectId)

    // Use the hybrid UserInfo while querying Thurloe
    thurloeDao.getTrialStatus(sudoUserInfo) flatMap {
      case Some(currentStatus) => {
        val currentState = currentStatus.state
        val newStatus = statusTransition(currentStatus)
        val newState = newStatus.state

        if (currentState == newState) {
          logger.warn(
            s"The user '${userInfo.userEmail}' is already in the trial state of '$newState'. " +
              s"No further action will be taken.")

          Future(StatusUpdate.Success)
        } else {
          logger.warn(s"Current trial state of the user '${userInfo.userEmail}' is '$currentState'")
          logger.warn("Checking if enabling is allowed from that state...")

          require(newState.nonEmpty, "Cannot transition to an unspecified state")

          // TODO: Handle invalid initial trial status by
          //    -adding another case to Trial.StatusUpdate
          //    -using that case to return a BadRequest from the parent method (i.e. updateUserState())
          assert(newState.get.isAllowedFrom(currentState),
            s"Cannot transition from $currentState to $newState")

          // TODO: Test the logic below by adding a mock ThurloeDAO whose saveTrialStatus() returns Failure
          // Save updates to user's trial status
          thurloeDao.saveTrialStatus(sudoUserInfo, newStatus) map {
            case Success(_) =>
              logger.warn(s"Updated profile saved as $newState; we are done!")
              StatusUpdate.Success
            case Failure(ex) =>
              StatusUpdate.ServerError(ex.getMessage)
          }
        }
      }
      // TODO: Respond with a more user-friendly error
      case None => {
        logger.warn(s"Trial status could not be found for ${userInfo.userEmail}")

        Future(StatusUpdate.Failure)
      }
    }
  }

  private def enrollUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    // get user's trial status, then check the current state
    thurloeDao.getTrialStatus(userInfo) flatMap {
      // can't determine the user's trial status; don't enroll
      case None => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 10)"))
      case Some(status) =>
        status.state match {
          // user already enrolled; don't re-enroll
          case Some(TrialStates.Enrolled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are already enrolled in a free trial. (Error 20)"))
          // user enabled (eligible) for trial, enroll!
          case Some(TrialStates.Enabled) => enrollUserInternal(userInfo, status)
          // user in some other state; don't enroll
          case Some(TrialStates.Disabled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 30)"))
          case Some(TrialStates.Terminated) => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 40)"))
          case None => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 50)"))
        }
    }
  }

  private def enrollUserInternal(userInfo: UserInfo, status: UserTrialStatus): Future[PerRequestMessage] = {

    val projectId = "fccredits-hafnium-peach-3794" // TODO: Make this status.billingProjectName when GAWB-2911 lands
    val saToken: WithAccessToken = AccessToken(OAuth2BearerToken(googleDAO.getTrialBillingManagerAccessToken))

    // 1. Check that the assigned Billing Project exists and contains exactly one member, the SA we used to create it
    rawlsDAO.getProjectMembers(projectId)(saToken) flatMap { members: Seq[RawlsBillingProjectMember] =>
      if (members.map(_.email.value) != Seq(HttpGoogleServicesDAO.trialBillingPemFileClientId)) {
        Future(RequestCompleteWithErrorReport(InternalServerError, "We could not process your enrollment. (Error 60 - billing project exists but cannot be used)"))
      } else {
        // 2. Add the user as Owner to the assigned Billing Project
        rawlsDAO.addUserToBillingProject(projectId, ProjectRoles.Owner, userInfo.userEmail)(userToken = saToken) flatMap { success: Boolean =>
          if (success) {
            // 3. Update the user's Thurloe profile to indicate Enrolled status
            thurloeDao.saveTrialStatus(userInfo, enrolledStatusFromStatus(status)) map {
              case Success(_) => RequestComplete(NoContent)
              case Failure(e) => RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. (Error 70 - ${e.getMessage})")
            }
          } else {
            Future(RequestCompleteWithErrorReport(InternalServerError, "We could not process your enrollment. (Error 75 - could not add user to billing project)"))
          }
        } recover {
          case e: FireCloudExceptionWithErrorReport =>
            RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. (Error 80 - ${e.errorReport.message})")
          case t: Throwable => RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. (Error 90 - ${t.getMessage})")
        }
      }
    } recover {
      case e: FireCloudExceptionWithErrorReport =>
        RequestCompleteWithErrorReport(InternalServerError,  s"We could not process your enrollment. (Error 100 - ${e.errorReport.message})")
      case t: Throwable => RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. (Error 110 - ${t.getMessage})")
    }
  }

  private def enrolledStatusFromStatus(status: UserTrialStatus): UserTrialStatus = {
    // build the new state that we want to persist to indicate the user is enrolled
    val now = Instant.now
    val expirationDate = now.plus(FireCloudConfig.Trial.durationDays, ChronoUnit.DAYS)
    status.copy(
      state = Some(TrialStates.Enrolled),
      enrolledDate = now,
      expirationDate = expirationDate
    )
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

    val saToken:WithAccessToken = AccessToken(OAuth2BearerToken(HttpGoogleServicesDAO.getTrialBillingManagerAccessToken))
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