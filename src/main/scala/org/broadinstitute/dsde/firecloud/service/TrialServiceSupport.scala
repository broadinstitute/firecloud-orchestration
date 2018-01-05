package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.time.Instant
import java.util
import java.util.Date

import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.dataaccess.{ThurloeDAO, TrialDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled, TrialState}
import org.broadinstitute.dsde.firecloud.model.Trial.{SpreadsheetResponse, TrialProject, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, Profile, ProfileWrapper, UserInfo, WorkbenchUserInfo}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait TrialServiceSupport extends LazyLogging {

  val trialDAO:TrialDAO

  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private val spreadsheetFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss ")
  private val zeroDate = Date.from(Instant.ofEpochMilli(0))
  // spreadsheet headers
  private val headers = List("Project Name", "User Subject Id", "Status", "Login Email", "Contact Email",
    "Enrollment Date", "Terminated Date", "Expiration Date", "User Agreement", "User Agreement Date",
    "First Name", "Last Name", "Organization", "City", "State", "Country").map(_.asInstanceOf[AnyRef]).asJava

  // the default set of values to use if a project has no users
  private val defaultValues = List.fill(headers.size() - 1)("") // We are placing this beside a project ID, so one fewer than # of columns


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


  def buildEnableUserStatus(userInfo: WorkbenchUserInfo, currentStatus: UserTrialStatus): UserTrialStatus = {
    val needsProject = currentStatus.state match {
      case None | Some(Disabled) => true
      case _ => false // either an invalid transition or noop
    }

    if (needsProject) {
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
      val trialProject = claimProject(1)

      logger.info(s"[trialaudit] assigned user ${userInfo.userEmail} (${userInfo.userSubjectId}) to project ${trialProject.name.value}")
      currentStatus.copy(userId = userInfo.userSubjectId, state = Some(Enabled), userAgreed = false, enabledDate = Instant.now, billingProjectName = Some(trialProject.name.value))
    } else {
      currentStatus.copy(state = Some(Enabled))
    }
  }

}
