package org.broadinstitute.dsde.firecloud.service

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.sheets.v4.model.SpreadsheetProperties
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SamDAO, ThurloeDAO, _}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impCreateProjectsResponse, impTrialProject, _}
import org.broadinstitute.dsde.firecloud.model.Trial.CreationStatuses.CreationStatus
import org.broadinstitute.dsde.firecloud.model.Trial.StatusUpdate.Attempt
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled, Terminated}
import org.broadinstitute.dsde.firecloud.model.Trial.{StatusUpdate, TrialStates, UserTrialStatus, _}
import org.broadinstitute.dsde.firecloud.model.{AccessToken, RequestCompleteWithErrorReport, UserInfo, WithAccessToken, WorkbenchUserInfo, _}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.TrialService._
import org.broadinstitute.dsde.firecloud.trial.ProjectManager.StartCreation
import org.broadinstitute.dsde.firecloud.utils.PermissionsSupport
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, RawlsBillingProjectName, RawlsUserEmail}
import spray.http.StatusCodes._
import spray.http.{OAuth2BearerToken, StatusCodes}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsString}
import spray.routing.RequestContext

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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
  case class RecordUserAgreement(userInfo: UserInfo) extends TrialServiceMessage
  case class CreateBillingReport(requestContext: RequestContext, userInfo: UserInfo) extends TrialServiceMessage
  case class UpdateBillingReport(requestContext: RequestContext, userInfo: UserInfo, spreadsheetId: String) extends TrialServiceMessage

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
  extends Actor with PermissionsSupport with SprayJsonSupport with TrialServiceSupport with LazyLogging {

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
    case RecordUserAgreement(userInfo) => recordUserAgreement(userInfo) pipeTo sender
    case CreateBillingReport(requestContext, userInfo) =>
      asTrialCampaignManager { createBillingReport(requestContext, userInfo) }(userInfo) pipeTo sender
    case UpdateBillingReport(requestContext, userInfo, spreadsheetId) =>
      asTrialCampaignManager { updateBillingReport(requestContext, userInfo, spreadsheetId) }(userInfo) pipeTo sender
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
      UserTrialStatus(userId = userInfo.userSubjectId, state = Some(Enabled), userAgreed = false, enabledDate = Instant.now, billingProjectName = Some(trialProject.name.value))
    } else {
      currentStatus.get.copy(state = Some(Enabled))
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
    val innerState = currentState match {
      case None => None
      case Some(status) => status.state
    }

    if (innerState.isEmpty || innerState != newState.state) {
      if (newState.state.isEmpty || !newState.state.get.isAllowedFrom(innerState))
        throw new FireCloudException(s"Cannot transition from $currentState.get.state to $newState.state")
      true
    } else {
      false
    }
  }

  private def executeStateTransitions(managerInfo: UserInfo, userEmails: Seq[String],
                                      statusTransition: (WorkbenchUserInfo, Option[UserTrialStatus]) => UserTrialStatus,
                                      transitionPostProcessing: (Attempt, Option[UserTrialStatus], UserTrialStatus) => Unit): Future[PerRequestMessage] = {
    var results: Seq[(String, String)] = Seq()
    userEmails.foreach { userEmail =>
      val finalStatus = samDao.adminGetUserByEmail(RawlsUserEmail(userEmail)) flatMap { regInfo =>
        val subId = regInfo.userInfo.userSubjectId
        val userInfo = WorkbenchUserInfo(subId, userEmail)
        thurloeDao.getTrialStatus(subId, managerInfo) flatMap { userTrialStatus =>
          val newStatus = statusTransition(userInfo, userTrialStatus)
          try {
            if (requiresStateTransition(userTrialStatus, newStatus)) {
              updateTrialStatus(managerInfo, userInfo, newStatus) map { stateResponse =>
                transitionPostProcessing(stateResponse, userTrialStatus, newStatus)
                StatusUpdate.toName(stateResponse)
              }
            } else {
              logger.warn(s"The user '${userInfo.userEmail}' is already in the trial state of '$newStatus.newState'. No further action will be taken.")
              Future.successful(StatusUpdate.toName(StatusUpdate.NoChangeRequired))
            }
          } catch {
            case ex: FireCloudException =>
              Future.successful(StatusUpdate.toName(StatusUpdate.Failure(ex.getMessage)))
            case t: Throwable =>
              StatusUpdate.ServerError(t.getMessage)
              Future.successful(StatusUpdate.toName(StatusUpdate.ServerError(t.getMessage)))
          }
        }
      } recoverWith {
        case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode.contains(StatusCodes.NotFound) =>
          Future.successful(StatusUpdate.toName(StatusUpdate.Failure("User not registered")))
        case ex: Exception =>
          Future.successful(StatusUpdate.toName(StatusUpdate.ServerError(ex.getMessage)))
      }
      // Use await here so that multiple Futures don't have a conflict when claiming a billing project
      val result = Await.result(finalStatus, scala.concurrent.duration.Duration.Inf)
      results = results ++ Seq((result, userEmail))
    }
    val sorted = results.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) }
    Future.successful(RequestComplete(sorted))
  }

  private def updateTrialStatus(managerInfo: UserInfo,
                                userInfo: WorkbenchUserInfo,
                                updatedTrialStatus: UserTrialStatus): Future[StatusUpdate.Attempt] = {

    thurloeDao.saveTrialStatus(userInfo.userSubjectId, managerInfo, updatedTrialStatus) map {
      case Success(_) => StatusUpdate.Success
      case Failure(ex) => StatusUpdate.ServerError(ex.getMessage)
    }
  }

  private def enrollUser(userInfo: UserInfo): Future[PerRequestMessage] = {
    // get user's trial status, then check the current state
    thurloeDao.getTrialStatus(userInfo.id, userInfo) flatMap {
      // can't determine the user's trial status; don't enroll
      case Some(status) =>
        status.state match {
          // user already enrolled; don't re-enroll
          case Some(TrialStates.Enrolled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are already enrolled in a free trial. (Error 20)"))
          // user enabled (eligible) for trial, enroll!
          case Some(TrialStates.Enabled) => {
            if (status.userAgreed) {
              enrollUserInternal(userInfo, status)
            } else {
              Future(RequestCompleteWithErrorReport(Forbidden, "You must agree to the trial terms to enroll. Please try again. (Error 25)"))
            }
          }
          // user in some other state; don't enroll
          case Some(TrialStates.Disabled) => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 30)"))
          case Some(TrialStates.Terminated) => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 40)"))
          case None => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 50)"))
        }
      case _ => Future(RequestCompleteWithErrorReport(BadRequest, "You are not eligible for a free trial. (Error 55)"))
    }
  }

  private def enrollUserInternal(userInfo: UserInfo, status: UserTrialStatus): Future[PerRequestMessage] = {

    val projectId = status.billingProjectName match {
      case Some(name: String) => name
      case None => {
        logger.warn(s"User ${userInfo.userEmail} attempted to enroll in trial but no billing project in profile.")
        throw new FireCloudException("We could not process your enrollment. Please contact support. (Error 56)")
      }
    }

    val saToken: WithAccessToken = AccessToken(OAuth2BearerToken(googleDAO.getTrialBillingManagerAccessToken))

    // 1. Check that the assigned Billing Project exists and contains exactly one member, the SA we used to create it
    rawlsDAO.getProjectMembers(projectId)(saToken) flatMap { members: Seq[RawlsBillingProjectMember] =>
      if (members.map(_.email.value) != Seq(googleDAO.getTrialBillingManagerEmail)) {
        // TODO: for resiliency, try running this operation again with a new project
        logger.warn(s"Cannot add user ${userInfo.userEmail} to billing project $projectId because it already contains members [${members.map(_.email.value).mkString(", ")}]")
        Future(RequestCompleteWithErrorReport(InternalServerError, "We could not process your enrollment. Please contact support. (Error 60)"))
      } else {
        // 2. Add the user as Owner to the assigned Billing Project
        rawlsDAO.addUserToBillingProject(projectId, ProjectRoles.Owner, userInfo.userEmail)(userToken = saToken) flatMap { _ =>
          // 3. Update the user's Thurloe profile to indicate Enrolled status
          thurloeDao.saveTrialStatus(userInfo.id, userInfo, enrolledStatusFromStatus(status)) flatMap {
            case Success(_) => Future(RequestComplete(NoContent)) // <- SUCCESS!
            case Failure(profileUpdateFail) => {
              // We couldn't update trial status, so clean up
              rawlsDAO.removeUserFromBillingProject(projectId, ProjectRoles.Owner, userInfo.userEmail)(userToken = saToken) map { _ =>
                logger.warn(s"Enrolling user ${userInfo.userEmail} failed at profile update: ${profileUpdateFail.getMessage}. User has been backed out of billing project $projectId.")
                RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. Please try again later. (Error 70)")
              } recover {
                case bpFail: Throwable => {
                  logger.warn(s"Enrolling user ${userInfo.userEmail} failed at profile update: ${profileUpdateFail.getMessage}. User is still in billing project $projectId due to cleanup failure: ${bpFail.getMessage}.")
                  RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. Please contact support. (Error 80)")
                }
              }
            }
          }
        } recover {
          case t: Throwable => {
            logger.warn(s"Attempt to add user ${userInfo.userEmail} to project $projectId failed: ${t.getMessage}. User profile has not been modified.")
            RequestCompleteWithErrorReport(InternalServerError, s"We could not process your enrollment. Please try again later. (Error 90)")
          }
        }
      }
    } recover {
      case t: Throwable => {
        logger.warn(s"Failed to list members of project $projectId on behalf of user ${userInfo.userEmail}: ${t.getMessage}")
        RequestCompleteWithErrorReport(InternalServerError, "We could not process your enrollment. Please try again later. (Error 110)")
      }
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

  private def recordUserAgreement(userInfo: UserInfo): Future[PerRequestMessage] = {
    // Thurloe errors are handled by the caller of this method
    thurloeDao.getTrialStatus(userInfo.id, userInfo) flatMap {
      case Some(status) =>
        status.state match {
          case Some(TrialStates.Enabled) =>
            thurloeDao.saveTrialStatus(userInfo.id, userInfo, status.copy(userAgreed = true)) flatMap {
              case Success(_) => Future(RequestComplete(NoContent))
              case Failure(ex) => Future(RequestComplete(InternalServerError, ex.getMessage))
            }
          case _ => Future(RequestCompleteWithErrorReport(Forbidden, "You are not eligible for a free trial."))
        }
      case None => Future(RequestCompleteWithErrorReport(Forbidden, "You are not eligible for a free trial."))
    }
  }

  private def createBillingReport(requestContext: RequestContext, userInfo: UserInfo): Future[PerRequestMessage] = {
    val properties: SpreadsheetProperties = makeSpreadsheetProperties("Trial Billing Project Report")
    val sheet: JsObject = googleDAO.createSpreadsheet(requestContext, userInfo, properties)
    Try(sheet.fields.getOrElse("spreadsheetId", JsString("")).asInstanceOf[JsString].value) match {
      case Success(spreadsheetId) if spreadsheetId.nonEmpty =>
        updateBillingReport(requestContext, userInfo, spreadsheetId)
      case Failure(e) =>
        logger.error(s"Unable to create new google spreadsheet for user context [${userInfo.userEmail}]: ${e.getMessage}")
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, e.getMessage))
    }
  }

  private def updateBillingReport(requestContext: RequestContext, userInfo: UserInfo, spreadsheetId: String): Future[PerRequestMessage] = {
    val majorDimension: String = "ROWS"
    val range: String = "Sheet1!A1"
    makeSpreadsheetValues(userInfo, trialDAO, thurloeDao, majorDimension, range).map { content =>
      Try (googleDAO.updateSpreadsheet(requestContext, userInfo, spreadsheetId, content)) match {
        case Success(updatedSheet) =>
          RequestComplete(OK, makeSpreadsheetResponse(spreadsheetId))
        case Failure(e) =>
          e match {
            case g: GoogleJsonResponseException =>
              logger.warn(s"Unable to update spreadsheet for user context [${userInfo.userEmail}]: ${g.getDetails.getMessage}")
              RequestCompleteWithErrorReport(g.getDetails.getCode, g.getDetails.getMessage)
            case _ => throw e
          }
      }
    }.recoverWith {
      case e: Throwable =>
        logger.error(s"Unable to update google spreadsheet for user context [${userInfo.userEmail}]: ${e.getMessage}")
        throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, e.getMessage))
    }
  }

}
