package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.time.Instant
import java.util
import java.util.Date

import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{SamDAO, ThurloeDAO, TrialDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.StatusUpdate.Attempt
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled, TrialState}
import org.broadinstitute.dsde.firecloud.model.Trial.{SpreadsheetResponse, StatusUpdate, TrialProject, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, Profile, ProfileWrapper, UserInfo, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.rawls.model.{RawlsBillingProjectName, RawlsUserEmail}
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait TrialServiceSupport extends LazyLogging with SprayJsonSupport {

  val trialDAO: TrialDAO
  val samDao: SamDAO
  val thurloeDao: ThurloeDAO
  implicit protected val executionContext: ExecutionContext

  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private val spreadsheetFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss ")
  private val zeroDate = Date.from(Instant.ofEpochMilli(0))
  // spreadsheet headers
  private val headers = List("Project Name", "User Subject Id", "Status", "Login Email", "Contact Email",
    "Enrollment Date", "Terminated Date", "Expiration Date", "User Agreement", "User Agreement Date",
    "First Name", "Last Name", "Organization", "City", "State", "Country").map(_.asInstanceOf[AnyRef]).asJava

  // the default set of values to use if a project has no users; one fewer than # of columns
  private val defaultValues = List.fill(headers.size() - 1)("")


  def makeSpreadsheetResponse(spreadsheetId: String): SpreadsheetResponse = {
    SpreadsheetResponse(s"https://docs.google.com/spreadsheets/d/$spreadsheetId")
  }

  def makeSpreadsheetProperties(title: String): SpreadsheetProperties = {
    val dateString = dateFormat.format(new Date())
    new SpreadsheetProperties().setTitle(s"$title: $dateString")
  }

  def makeSpreadsheetValues(managerInfo: UserInfo, trialDAO: TrialDAO, thurloeDAO: ThurloeDAO, majorDimension: String, range: String)
    (implicit executionContext: ExecutionContext): Future[ValueRange] = {

    // get the list of projects from ES
    val projects: Seq[TrialProject] = trialDAO.projectReport
    // find the Thurloe KVPs for any users referenced in those projects
    val profileWrappers:Future[Seq[ProfileWrapper]] = Future.sequence(projects.filter(p => p.user.isDefined).map { p =>
      thurloeDAO.getAllKVPs(p.user.get.userSubjectId, managerInfo) map { kvpOption =>
        kvpOption.getOrElse(ProfileWrapper(p.user.get.userSubjectId, List.empty[FireCloudKeyValue]))
      }
    })

    // resolve the Thurloe KVPs future
    profileWrappers.map { wrappers =>
      // cache a map of subjectId -> ProfileWrapper for efficient lookups later
      val userKVPMap: Map[String, ProfileWrapper] = wrappers.map(pw => pw.userId -> pw).toMap

      // loop over all projects (including those that have no defined user) and build spreadsheet rows
      val rows: List[util.List[AnyRef]] = projects.map { trialProject =>
        val rowStrings = trialProject.user match {
          case Some(user) => getTrialUserInformation(user, userKVPMap).toSpreadsheetValues
          case None => defaultValues
        }

        (List(trialProject.name.value) ++ rowStrings).map(_.asInstanceOf[AnyRef]).asJava
      }.toList

      val values: util.List[util.List[AnyRef]] = (headers :: rows).asJava
      new ValueRange().setMajorDimension(majorDimension).setRange(range).setValues(values)
    }
  }

  // convenience method to pull user information from options
  private def getTrialUserInformation(user: WorkbenchUserInfo, userKVPMap: Map[String,ProfileWrapper]): SpreadsheetRow = {

    val userSubjectId = user.userSubjectId

    // guarantee we get KVPs from our cache. We could instead throw an error if subjectid is not found;
    // we expect to always find the user.
    val profileWrapper: ProfileWrapper = userKVPMap.getOrElse(userSubjectId,
      ProfileWrapper(userSubjectId, List.empty[FireCloudKeyValue]))
    val profile = Profile(profileWrapper)
    val status = UserTrialStatus(profileWrapper)

    SpreadsheetRow(
      userSubjectId = status.userId,
      state = status.state,
      userEmail = user.userEmail,
      enrollmentDate = status.enrolledDate,
      terminatedDate = status.terminatedDate,
      expiredDate = status.expirationDate,
      userAgreed = status.userAgreed,
      userAgreedDate = status.enrolledDate, // TODO: should be separate date
      firstName = profile.firstName,
      lastName = profile.lastName,
      contactEmail = profile.contactEmail.getOrElse(user.userEmail),
      institute = profile.institute,
      programLocationCity = profile.programLocationCity,
      programLocationState = profile.programLocationState,
      programLocationCountry = profile.programLocationCountry
    )
  }

  case class SpreadsheetRow(
      userSubjectId: String,
      state: Option[TrialState],
      userEmail: String, // sign-in email
      enrollmentDate: Instant,
      terminatedDate: Instant,
      expiredDate: Instant,
      userAgreed: Boolean,
      userAgreedDate: Instant, // time the user signed the eula
      firstName: String,
      lastName: String ,
      contactEmail: String, // preferred contact email
      institute: String, // organization
      programLocationCity: String,
      programLocationState: String,
      programLocationCountry: String
  ) {
    def toSpreadsheetValues: List[String] = {
      List(
        userSubjectId,
        if (state.isDefined) state.get.toString else "Unknown",
        userEmail,
        contactEmail,
        instantToSpreadsheetString(enrollmentDate),
        instantToSpreadsheetString(terminatedDate),
        instantToSpreadsheetString(expiredDate),
        if (userAgreed) "Accepted" else "Not Accepted",
        instantToSpreadsheetString(userAgreedDate),
        firstName,
        lastName,
        institute,
        programLocationCity,
        programLocationState,
        programLocationCountry
      )
    }
  }

  private def instantToSpreadsheetString(instant: Instant): String = {
    val date = Date.from(instant)
    if (date.after(zeroDate)) spreadsheetFormat.format(date) else ""
  }

  //

  /**
    * Enables the current user for free credits. Should only be used during the registration process.
    * @param userInfo the current user
    * @return true if enabling succeeded; will throw an exception if failed.
    */
  def enableSelfForFreeCredits(userInfo: UserInfo): Future[Boolean] = {
    val numAvailable:Long = trialDAO.countProjects.getOrElse("available", 0L)

    if (numAvailable < 1) {
      Future.failed(new FireCloudException(s"There are only $numAvailable free credit projects available. Please retry in a few minutes."))
    } else {
      // log an error if project pool is running low - warning will be noticed by team, who can take action
      if (numAvailable < FireCloudConfig.Trial.projectBuferSize)
        logger.error(s"There are only $numAvailable free credit projects available. Create more immediately!")

      // following functions are located in TrialServiceSupport
      executeStateTransitions(userInfo, Seq(userInfo.userEmail), buildEnableUserStatus, enableUserPostProcessing).map{ _ => true }
    }
  }

  def buildEnableUserStatus(userInfo: WorkbenchUserInfo, currentStatus: UserTrialStatus): UserTrialStatus = {
    val needsProject = currentStatus.state match {
      case None | Some(Disabled) => true
      case _ => false // either an invalid transition or noop
    }

    if (needsProject) {
      val trialProject = claimProjectWithRetries(userInfo)
      logger.info(s"[trialaudit] assigned user ${userInfo.userEmail} (${userInfo.userSubjectId}) to project ${trialProject.name.value}")
      currentStatus.copy(userId = userInfo.userSubjectId, state = Some(Enabled), userAgreed = false, enabledDate = Instant.now, billingProjectName = Some(trialProject.name.value))
    } else {
      currentStatus.copy(state = Some(Enabled))
    }
  }

  def claimProjectWithRetries(userInfo:WorkbenchUserInfo):TrialProject = {
    // how many times should we try to find an available project? Retries here help when multiple users are enabled at once
    val numAttempts = 50

    def claimProject(attempt:Int):TrialProject = {
      Try(trialDAO.claimProjectRecord(WorkbenchUserInfo(userInfo.userSubjectId, userInfo.userEmail))) match {
        case Success(s) => s
        case Failure(f) =>
          if (attempt >= numAttempts) {
            throw new FireCloudException(s"Could not claim a project while enabling user ${userInfo.userSubjectId} " +
              s"(${userInfo.userEmail}):", f)
          } else {
            logger.debug(s"buildEnableUserStatus retrying claim; attempt $attempt")
            claimProject(attempt + 1)
          }
      }
    }
    claimProject(1)
  }

  def enableUserPostProcessing(updateStatus: Attempt, prevStatus: UserTrialStatus, newStatus: UserTrialStatus): Unit = {
    if (updateStatus != StatusUpdate.Success && newStatus.billingProjectName.isDefined) {
      logger.warn(
        s"[trialaudit] The user '${newStatus.userId}' failed to be enabled, releasing the billing project '${newStatus.billingProjectName.get}' back into the available pool.")
      trialDAO.releaseProjectRecord(RawlsBillingProjectName(newStatus.billingProjectName.get))
    }
  }

  def executeStateTransitions(managerInfo: UserInfo, userEmails: Seq[String],
                                      statusTransition: (WorkbenchUserInfo, UserTrialStatus) => UserTrialStatus,
                                      transitionPostProcessing: (Attempt, UserTrialStatus, UserTrialStatus) => Unit): Future[PerRequestMessage] = {

    def checkAndUpdateState(userInfo: WorkbenchUserInfo, userTrialStatus: UserTrialStatus, newStatus: UserTrialStatus): Future[String] = {
      Try(requiresStateTransition(userTrialStatus, newStatus)) match {
        case Success(isRequired) =>
          if (isRequired) {
            updateTrialStatus(managerInfo, userInfo, newStatus) map { stateResponse =>
              transitionPostProcessing(stateResponse, userTrialStatus, newStatus)
              StatusUpdate.toName(stateResponse)
            }
          } else {
            logger.info(s"The user '${userInfo.userEmail}' is already in the trial state of '${newStatus.state.getOrElse("")}'. No further action will be taken.")
            Future.successful(StatusUpdate.toName(StatusUpdate.NoChangeRequired))
          }
        case Failure(t: Throwable) => Future.successful(throwableToStatus(t))
      }
    }

    val userTransitions = userEmails.map { userEmail =>
      val status = Try(for {
        regInfo <- samDao.adminGetUserByEmail(RawlsUserEmail(userEmail))
        subId = regInfo.userInfo.userSubjectId
        userInfo = WorkbenchUserInfo(subId, userEmail)
        userTrialStatus <- thurloeDao.getTrialStatus(subId, managerInfo)
        newStatus = statusTransition(userInfo, userTrialStatus)
        result <- checkAndUpdateState(userInfo, userTrialStatus, newStatus)
      } yield result) match {
        case Success(s) => s
        case Failure(t: Throwable) =>
          Future.successful(throwableToStatus(t))
      }
      status map { finalStatus: String => (finalStatus, userEmail) } recover {
        case ex: Exception =>
          (StatusUpdate.toName(StatusUpdate.ServerError(ex.getMessage)), userEmail)
      }
    }

    Future.sequence(userTransitions) map { results =>
      val sorted: Map[String, Seq[String]] = results.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) }
      RequestComplete(sorted)
    }
  }

  def updateTrialStatus(managerInfo: UserInfo,
                                userInfo: WorkbenchUserInfo,
                                updatedTrialStatus: UserTrialStatus): Future[StatusUpdate.Attempt] = {
    thurloeDao.saveTrialStatus(userInfo.userSubjectId, managerInfo, updatedTrialStatus) map {
      case Success(_) =>
        logger.info(s"[trialaudit] updated user ${userInfo.userEmail} (${userInfo.userSubjectId}) to state ${updatedTrialStatus.state.getOrElse("")}")
        StatusUpdate.Success
      case Failure(ex) => StatusUpdate.ServerError(ex.getMessage)
    }
  }

  def requiresStateTransition(currentState: UserTrialStatus, newState: UserTrialStatus): Boolean = {
    // this check needs to happen first for idempotent behavior
    if (currentState.state == newState.state)
      false
    else {
      // make sure the state transition is valid
      if (newState.state.isEmpty || !newState.state.get.isAllowedFrom(currentState.state))
        throw new FireCloudException(s"Cannot transition from ${currentState.state} to $newState.state.get")
      true // indicates transition is required
    }
  }


  def throwableToStatus(t: Throwable): String = {
    t match {
      case exr: FireCloudExceptionWithErrorReport =>
        if (exr.errorReport.statusCode.contains(StatusCodes.NotFound))
          StatusUpdate.toName(StatusUpdate.Failure("User not registered"))
        else StatusUpdate.toName(StatusUpdate.Failure(exr.errorReport.message))
      case ex: FireCloudException =>
        StatusUpdate.toName(StatusUpdate.Failure(ex.getMessage))
      case _ =>
        StatusUpdate.toName(StatusUpdate.ServerError(t.getMessage))
    }
  }

}
