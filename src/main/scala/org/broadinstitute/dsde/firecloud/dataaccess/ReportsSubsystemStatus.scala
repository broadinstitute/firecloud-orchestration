package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{DropwizardHealth, SubsystemStatus}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by anichols on 4/21/17.
  */
trait ReportsSubsystemStatus {

  def status: Future[SubsystemStatus]

  def serviceName: String

  def getStatusFromDropwizardChecks(futureStatus: Future[Map[String, DropwizardHealth]])(implicit ec: ExecutionContext): Future[SubsystemStatus] = {
    futureStatus map { dwStatus =>
      val ok = dwStatus.values.forall(_.healthy)
      val errors = dwStatus.values.filter { dw =>
        !dw.healthy && dw.message.isDefined
      }.map(_.message.get).toList
      errors match {
        case x if x.isEmpty => SubsystemStatus(ok)
        case _ => SubsystemStatus(ok, Some(errors))
      }
    }
  }

}
