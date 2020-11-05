package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

package object model {
  implicit val errorReportSource = ErrorReportSource("FireCloud")

  import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
  import spray.json._

  implicit val defaultErrorReportRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case resp@HttpResponse(statusCode, _, ent: HttpEntity.Strict, _) => {
      // since all Akka default rejection responses are Strict this will handle all rejections

      // if the rejection response is not already json, make it json.
      val entityString = ent.data.utf8String
      Try(entityString.parseJson) match {
        case Success(_) => resp
        case Failure(_) =>
          // N.B. this handler previously manually escaped double quotes in the entityString. We don't need to do that,
          // since the .toJson below handles escaping internally.
          resp.withEntity(HttpEntity(ContentTypes.`application/json`, ErrorReport(statusCode, entityString).toJson.prettyPrint))
      }
    }
  }

  implicit val malformedRequestContentRejectionHandler = RejectionHandler.newBuilder().handle {
    case MalformedRequestContentRejection(errorMsg, _) =>
      complete { (StatusCodes.BadRequest, ErrorReport(StatusCodes.BadRequest, errorMsg)) }
  }.result()

}
