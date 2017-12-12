package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.time.Instant
import java.util
import java.util.Date

import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{ThurloeDAO, TrialDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled}
import org.broadinstitute.dsde.firecloud.model.Trial.{SpreadsheetResponse, TrialProject, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WorkbenchUserInfo}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

trait TrialServiceSupport extends LazyLogging {

  val trialDAO:TrialDAO

  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private val enrollmentFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss ")

  def makeSpreadsheetResponse(spreadsheetId: String): SpreadsheetResponse = {
    SpreadsheetResponse(s"https://docs.google.com/spreadsheets/d/$spreadsheetId")
  }

  def makeSpreadsheetProperties(title: String): SpreadsheetProperties = {
    val dateString = dateFormat.format(new Date())
    new SpreadsheetProperties().setTitle(s"$title: $dateString")
  }

  def makeSpreadsheetValues(managerInfo: UserInfo, trialDAO: TrialDAO, thurloeDAO: ThurloeDAO, majorDimension: String, range: String)
    (implicit executionContext: ExecutionContext): Future[ValueRange] = {
    val projects: Seq[TrialProject] = trialDAO.projectReport
    val trialStatuses = Future.sequence(projects.map { p =>
      if (p.user.isDefined)
        thurloeDAO.getTrialStatus(p.user.get.userSubjectId, managerInfo)
      else
        Future[Option[UserTrialStatus]](None)
    })
    trialStatuses.map { userTrialStatuses =>
      val headers = List("Project Name", "User Subject Id", "User Email", "Enrollment Date", "Terminated Date", "User Agreement").map(_.asInstanceOf[AnyRef]).asJava
      val rows: List[util.List[AnyRef]] = projects.map { trialProject =>
        val (userSubjectId, userEmail, enrollmentDate, terminatedDate, userAgreed) = getTrialUserInformation(trialProject.user, userTrialStatuses)
        List(trialProject.name.value, userSubjectId, userEmail, enrollmentDate, terminatedDate, userAgreed).map(_.asInstanceOf[AnyRef]).asJava
      }.toList
      val values: util.List[util.List[AnyRef]] = (headers :: rows).asJava
      new ValueRange().setMajorDimension(majorDimension).setRange(range).setValues(values)
    }
  }

  // convenience method to pull user information from options
  private def getTrialUserInformation(user: Option[WorkbenchUserInfo], userTrialStatuses: Seq[Option[UserTrialStatus]]): (String, String, String, String, String) = {
    if (user.isDefined) {
      val userSubjectId = user.get.userSubjectId
      val userEmail = user.get.userEmail
      val userTrialStatus = userTrialStatuses.find { trialStatus =>
        trialStatus.isDefined && trialStatus.get.userId.equals(userSubjectId)
      }.flatten
      val (enrollmentDate, terminatedDate, userAgreed) = if (userTrialStatus.isDefined) {
        val trialStaus = userTrialStatus.get
        val zeroDate = Date.from(Instant.ofEpochMilli(0))
        val enrollDate = Date.from(userTrialStatus.get.enrolledDate)
        val enrollmentDateString = if (enrollDate.after(zeroDate))
          enrollmentFormat.format(Date.from(userTrialStatus.get.enrolledDate))
        else
          ""
        val termDate = Date.from(userTrialStatus.get.terminatedDate)
        val termDateString = if (termDate.after(zeroDate))
          enrollmentFormat.format(termDate)
        else
          ""
        val userAgreed = if (trialStaus.userAgreed)
          "Accepted"
        else
          "Not Accepted"
        (enrollmentDateString, termDateString, userAgreed)
      } else {
        ("", "", "")
      }
      (userSubjectId, userEmail, enrollmentDate, terminatedDate, userAgreed)
    } else {
      ("", "", "", "", "")
    }
  }

  def buildEnableUserStatus(userInfo: WorkbenchUserInfo, currentStatus: Option[UserTrialStatus]): UserTrialStatus = {
    val needsProject = currentStatus match {
      case None => true
      case Some(statusObj) => statusObj.state match {
        case None | Some(Disabled) => true
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

}
