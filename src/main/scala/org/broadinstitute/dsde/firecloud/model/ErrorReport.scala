package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
import spray.client.pipelining.unmarshal
import spray.http._
import spray.json._
import spray.httpx.SprayJsonSupport._

import scala.util.Try

object ErrorReportExtensions {
  object FCErrorReport {

    def apply(response: HttpResponse)(implicit ers: ErrorReportSource): ErrorReport = {
      import spray.httpx.unmarshalling._
      val (message, causes) = response.entity.as[ErrorReport] match {
        case Right(re) => (re.message, Seq(re))
        case Left(err) => (response.entity.asString, Seq.empty)
      }
      new ErrorReport(ers.source, message, Option(response.status), causes, Seq.empty, None)
    }

    def tryUnmarshal(response: HttpResponse) =
      Try { unmarshal[ErrorReport].apply(response) }
  }
}

object RequestCompleteWithErrorReport {

  def apply(statusCode: StatusCode, message: String) =
    RequestComplete(statusCode, ErrorReport(statusCode, message))

  def apply(statusCode: StatusCode, message: String, throwable: Throwable) =
    RequestComplete(statusCode, ErrorReport(statusCode, message, throwable))

  def apply(statusCode: StatusCode, message: String, causes: Seq[ErrorReport]) =
    RequestComplete(statusCode, ErrorReport(statusCode, message, causes))
}

object HttpResponseWithErrorReport {

  def apply(statusCode: StatusCode, message: String) =
    HttpResponse(statusCode, ErrorReport(statusCode, message).toJson.compactPrint)

  def apply(statusCode: StatusCode, throwable: Throwable) =
    HttpResponse(statusCode, ErrorReport(statusCode, throwable).toJson.compactPrint)

  def apply(statusCode: StatusCode, message: String, throwable: Throwable) =
    HttpResponse(statusCode, ErrorReport(statusCode, message, throwable).toJson.compactPrint)

}
