package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, MethodRejection, RejectionHandler}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}

import scala.language.implicitConversions

package object model {
  implicit val errorReportSource = ErrorReportSource("FireCloud")

  import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
  import spray.json._

  implicit val defaultErrorReportRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case resp@HttpResponse(statusCode, _, ent: HttpEntity.Strict, _) => {
      // since all Akka default rejection responses are Strict this will handle all rejections
      val message = ent.data.utf8String.replaceAll("\"", """\"""")

      resp.withEntity(HttpEntity(ContentTypes.`application/json`, ErrorReport(statusCode, message).toJson.toString))
    }
  }

  implicit val malformedRequestContentRejectionHandler = RejectionHandler.newBuilder().handle {
    case MalformedRequestContentRejection(errorMsg, _) =>
      complete { (StatusCodes.BadRequest, ErrorReport(StatusCodes.BadRequest, errorMsg)) }
  }

}
