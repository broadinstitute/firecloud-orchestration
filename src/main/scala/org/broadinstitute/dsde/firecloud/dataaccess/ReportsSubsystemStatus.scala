package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.{DropwizardHealth, SubsystemStatus}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource

import scala.concurrent.{ExecutionContext, Future}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.http.HttpResponse
import spray.json.DefaultJsonProtocol._
import spray.json._

/**
  * Created by anichols on 4/21/17.
  */
trait ReportsSubsystemStatus extends LazyLogging {

  def status: Future[SubsystemStatus]

  def serviceName: String

  def getStatusFromDropwizardChecks(response: Future[HttpResponse])(implicit ec: ExecutionContext): Future[SubsystemStatus] = {
    response map { resp =>
      val dwStatus = resp.entity.asString.parseJson.convertTo[Map[String, DropwizardHealth]]
      val ok = dwStatus.values.forall(_.healthy)
      val errors = dwStatus.
        filter(dw => !dw._2.healthy).
        map(dw => s"Error in ${dw._1}: ${dw._2.message.getOrElse("unspecified error")}").toList
      if (ok)
        SubsystemStatus(ok)
      else
        SubsystemStatus(ok, Some(errors))
    }
  }

}
