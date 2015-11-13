package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import spray.client.pipelining._
import spray.http.MediaTypes._
import spray.http._
import spray.routing.{MalformedRequestContentRejection, RejectionHandler}

import scala.util.{Success, Try}

import spray.httpx.SprayJsonSupport._
import ModelJsonProtocol._
import spray.json._

case class ErrorReport (
  source: String,
  message: String,
  statusCode: Option[StatusCode] = None,
  causes: Seq[ErrorReport] = Seq(),
  stackTrace: Seq[StackTraceElement] = Seq())

object ErrorReport extends ((String,String,Option[StatusCode],Seq[ErrorReport],Seq[StackTraceElement]) => ErrorReport) {
  private val SOURCE = "FireCloud"

  def apply(throwable: Throwable) =
    new ErrorReport(SOURCE, message(throwable), None, causes(throwable), throwable.getStackTrace)

  def apply(statusCode: StatusCode, throwable: Throwable) =
    new ErrorReport(SOURCE, message(throwable), Option(statusCode), causes(throwable), throwable.getStackTrace)

  def apply(statusCode: StatusCode, message: String) =
    new ErrorReport(SOURCE, message, Option(statusCode), Seq.empty, Seq.empty)

  def apply(statusCode: StatusCode, message: String, throwable: Throwable) =
    new ErrorReport(SOURCE, message, Option(statusCode), causes(throwable), throwable.getStackTrace)

  def apply(statusCode: StatusCode, message: String, causes: Seq[ErrorReport]): ErrorReport =
    new ErrorReport(SOURCE, message, Option(statusCode), causes, Seq.empty)

  def apply(response: HttpResponse) = {
    val causes = tryUnmarshal(response) match {
      case Success(re) => Seq(re)
      case _ => Seq.empty
    }
    new ErrorReport(SOURCE, response.entity.asString, Option(response.status), causes, Seq.empty)
  }

  def tryUnmarshal(response: HttpResponse) =
    Try { unmarshal[ErrorReport].apply(response) }

  private def message(throwable: Throwable) = Option(throwable.getMessage).getOrElse(throwable.getClass.getSimpleName)

  private def causes(throwable: Throwable): Array[ErrorReport] = causeThrowables(throwable).map(ErrorReport(_))

  private def causeThrowables(throwable: Throwable) = {
    if (throwable.getSuppressed.nonEmpty || throwable.getCause == null) throwable.getSuppressed
    else Array(throwable.getCause)
  }

  // adapted from https://gist.github.com/jrudolph/9387700
  implicit val errorReportRejectionHandler = RejectionHandler {
    case MalformedRequestContentRejection(errorMsg, _) :: _ =>
      ctx => ctx.complete(StatusCodes.BadRequest, ErrorReport(StatusCodes.BadRequest, errorMsg))
    case x if RejectionHandler.Default.isDefinedAt(x) =>
      ctx => RejectionHandler.Default(x) {
        ctx.withHttpResponseMapped {
          case resp@HttpResponse(statusCode, HttpEntity.NonEmpty(ContentType(`text/plain`, _), msg), _, _) =>
            import spray.httpx.marshalling
            resp.withEntity(marshalling.marshalUnsafe(ErrorReport(statusCode, msg.asString)))
        }
      }
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

}