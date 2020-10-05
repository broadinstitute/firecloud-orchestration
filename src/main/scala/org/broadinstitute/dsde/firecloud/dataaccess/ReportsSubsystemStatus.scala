package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling._
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impDropwizardHealth
import org.broadinstitute.dsde.firecloud.model.DropwizardHealth
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by anichols on 4/21/17.
  */
trait ReportsSubsystemStatus {

  implicit val materializer: Materializer

  def status: Future[SubsystemStatus]

  def serviceName: String

  def getStatusFromDropwizardChecks(response: Future[HttpResponse])(implicit ec: ExecutionContext): Future[SubsystemStatus] = {
    response map { resp =>
      Unmarshal(resp).to[Map[String, DropwizardHealth]].map { dwStatus =>
        val ok = dwStatus.values.forall(_.healthy)
        val errors = dwStatus.
          filter(dw => !dw._2.healthy).
          map(dw => s"Error in ${dw._1}: ${dw._2.message.getOrElse("unspecified error")}").toList
        if (ok)
          SubsystemStatus(ok, None)
        else
          SubsystemStatus(ok, Some(errors))
      }
    }
  }

}
