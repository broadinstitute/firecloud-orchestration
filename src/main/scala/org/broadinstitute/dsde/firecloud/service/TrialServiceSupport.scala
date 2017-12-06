package org.broadinstitute.dsde.firecloud.service

import java.text.SimpleDateFormat
import java.util
import java.util.Date

import com.google.api.services.sheets.v4.model.{SpreadsheetProperties, ValueRange}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess.{ThurloeDAO, TrialDAO}
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialProject, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WorkbenchUserInfo}

import scala.concurrent.{ExecutionContext, Future}

trait TrialServiceSupport extends LazyLogging {

  private val dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  def makeSpreadsheetProperties(title: String): SpreadsheetProperties = {
    val dateString = dateFormat.format(new Date())
    new SpreadsheetProperties().setTitle(s"$title: $dateString")
  }

  // TODO: Look further into the Onix "User Agreement" field
  def makeSpreadsheetValues(managerInfo: UserInfo, trialDAO: TrialDAO, thurloeDAO: ThurloeDAO, majorDimension: String, range: String)
    (implicit executionContext: ExecutionContext): Future[ValueRange] = {
    import scala.collection.JavaConverters._
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
        val (userSubjectId, userEmail, enrollmentDate, terminatedDate) = getTrialUserInformation(trialProject.user, userTrialStatuses)
        List(trialProject.name.value, userSubjectId, userEmail, enrollmentDate, terminatedDate, "Accepted").map(_.asInstanceOf[AnyRef]).asJava
      }.toList
      val values: util.List[util.List[AnyRef]] = (headers :: rows).asJava
      new ValueRange().setMajorDimension(majorDimension).setRange(range).setValues(values)
    }
  }

  // convenience method to pull user information from options
  private def getTrialUserInformation(user: Option[WorkbenchUserInfo], userTrialStatuses: Seq[Option[UserTrialStatus]]): (String, String, String, String) = {
    if (user.isDefined) {
      val userSubjectId = user.get.userSubjectId
      val userEmail = user.get.userEmail
      val userTrialStatus = userTrialStatuses.find { trialStatus =>
        trialStatus.isDefined && trialStatus.get.userId.equals(userSubjectId)
      }.flatten
      val enrollmentDate = if (userTrialStatus.isDefined)
        dateFormat.format(Date.from(userTrialStatus.get.enrolledDate))
      else
        ""
      val terminatedDate = if (userTrialStatus.isDefined)
        dateFormat.format(Date.from(userTrialStatus.get.terminatedDate))
      else
        ""
      (userSubjectId, userEmail, enrollmentDate, terminatedDate)
    } else {
      ("", "", "", "")
    }
  }

}
