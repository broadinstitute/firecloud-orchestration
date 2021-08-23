package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling._
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.model.DUOS.ConsentStatus
import org.broadinstitute.dsde.firecloud.model.DropwizardHealth
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impDuosConsentStatus, impDropwizardHealth}
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by anichols on 4/21/17.
  */
trait ReportsSubsystemStatus extends SprayJsonSupport {

  def status: Future[SubsystemStatus]

  def serviceName: String

  def getStatusFromDropwizardChecks(response: Future[HttpResponse])(implicit ec: ExecutionContext, materializer: Materializer): Future[SubsystemStatus] = {
    response flatMap { resp =>
      Unmarshal(resp).to[ConsentStatus].map { status =>
        val ok = status.ok.get
        val errors = status.systems.get.
          filter(dw => !dw._2.healthy).
          map(dw => s"Error in ${dw._1}: ${dw._2.message.getOrElse("unspecified error")}").toList
        if (ok)
          SubsystemStatus(ok, None)
        else
          SubsystemStatus(ok, Some(errors))
      }.recoverWith {
        case _: Throwable =>
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

}
