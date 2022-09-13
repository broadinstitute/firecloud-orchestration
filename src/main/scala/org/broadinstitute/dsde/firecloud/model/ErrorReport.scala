package org.broadinstitute.dsde.firecloud.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCode}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object ErrorReportExtensions {
  object FCErrorReport extends SprayJsonSupport {

    def apply(response: HttpResponse)(implicit ers: ErrorReportSource, executionContext: ExecutionContext, mat: Materializer): Future[ErrorReport] = {
      // code prior to creation of this error report may have already consumed the response entity

      response.entity match {
        case HttpEntity.Strict(contentType, data) =>
          val entityString = data.decodeString(java.nio.charset.Charset.defaultCharset())
          Unmarshal(entityString).to[ErrorReport].map { re =>
            new ErrorReport(ers.source, re.message, Option(response.status), Seq(re), Seq.empty, None)
          } recover {
            case _ =>
              new ErrorReport(ers.source, entityString, Option(response.status), Seq.empty, Seq.empty, None)
          }
        case _ =>
          val fallbackMessage = Try(response.toString()).toOption.getOrElse("Unexpected error")
          Future.successful(new ErrorReport(ers.source, fallbackMessage, Option(response.status), Seq.empty, Seq.empty, None))
      }
//
//      Unmarshal(response).to[ErrorReport].map { re =>
//        new ErrorReport(ers.source, re.message, Option(response.status), Seq(re), Seq.empty, None)
//      } recoverWith {
//        case _ => Unmarshal(response).to[String].map { message =>
//          new ErrorReport(ers.source, message, Option(response.status), Seq.empty, Seq.empty, None)
//        }
//      }
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
