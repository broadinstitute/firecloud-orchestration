package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.model.ErrorReport
import spray.http.StatusCode

class FireCloudException(message: String = null, cause: Throwable = null) extends Exception(message, cause)

class FireCloudExceptionWithErrorReport(val errorReport: ErrorReport) extends FireCloudException(errorReport.toString)

/*
case class ErrorReport(source: String, message: String, statusCode: Option[StatusCode], causes: Seq[ErrorReport], stackTrace: Seq[StackTraceElement])

object ErrorReport extends ((String,String,Option[StatusCode],Seq[ErrorReport],Seq[StackTraceElement]) => ErrorReport) {
  val SOURCE = "rawls"

  def apply(statusCode: StatusCode, message: String): ErrorReport =
    new ErrorReport(SOURCE,message,Option(statusCode),Seq.empty,Seq.empty)

  def apply(statusCode: StatusCode, message: String, cause: ErrorReport): ErrorReport =
    new ErrorReport(SOURCE,message,Option(statusCode),Seq(cause),Seq.empty)

  def apply(statusCode: StatusCode, message: String, causes: Seq[ErrorReport]): ErrorReport =
    new ErrorReport(SOURCE,message,Option(statusCode),causes,Seq.empty)

  def apply(throwable: Throwable): ErrorReport =
    new ErrorReport(SOURCE,message(throwable),None,causes(throwable),throwable.getStackTrace)

  def apply(throwable: Throwable, statusCode: StatusCode): ErrorReport =
    new ErrorReport(SOURCE,message(throwable),Some(statusCode),causes(throwable),throwable.getStackTrace)

  def apply(message: String, cause: ErrorReport) =
    new ErrorReport(SOURCE,message,None,Seq(cause),Seq.empty)

  def apply(message: String, causes: Seq[ErrorReport]) =
    new ErrorReport(SOURCE,message,None,causes,Seq.empty)

  def message(throwable: Throwable) = Option(throwable.getMessage).getOrElse(throwable.getClass.getSimpleName)
  def causes(throwable: Throwable): Array[ErrorReport] = causeThrowables(throwable).map(ErrorReport(_))
  private def causeThrowables(throwable: Throwable) = {
    if (throwable.getSuppressed.nonEmpty || throwable.getCause == null) throwable.getSuppressed
    else Array(throwable.getCause)
  }
}
*/