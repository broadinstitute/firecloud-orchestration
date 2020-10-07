package org.broadinstitute.dsde.firecloud.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpResponse, StatusCode}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
import spray.json._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import scala.util.{Failure, Success}


import scala.util.Try

object ErrorReportExtensions {
  object FCErrorReport extends SprayJsonSupport {

    def apply(response: HttpResponse)(implicit ers: ErrorReportSource): ErrorReport = {
      val (message, causes) = Try(Unmarshal(response.entity).to[ErrorReport]) match {
        case Failure(re) => (re.getMessage, Seq(re))
        case Success(err) => (response.entity.toString, Seq.empty)
      }
      new ErrorReport(ers.source, message, Option(response.status), causes, Seq.empty, None)
    }
  }
}

object RequestCompleteWithErrorReport extends SprayJsonSupport {

  def apply(statusCode: StatusCode, message: String) =
    RequestComplete(statusCode, ErrorReport(statusCode, message))

  def apply(statusCode: StatusCode, message: String, throwable: Throwable) =
    RequestComplete(statusCode, ErrorReport(statusCode, message, throwable))

  def apply(statusCode: StatusCode, message: String, causes: Seq[ErrorReport]) =
    RequestComplete(statusCode, ErrorReport(statusCode, message, causes))
}
