package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.RejectionHandler
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

package object model {
  implicit val errorReportSource = ErrorReportSource("FireCloud")

  import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
  import spray.json._

  /*
    Rejection handler: if the response from the rejection is not already json, make it json.
   */
  implicit val defaultErrorReportRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case resp@HttpResponse(statusCode, _, ent: HttpEntity.Strict, _) => {

      // since all Akka default rejection responses are Strict this will handle all rejections
      val entityString = ent.data.utf8String
      Try(entityString.parseJson) match {
        case Success(_) =>
          resp
        case Failure(_) =>
          // N.B. this handler previously manually escaped double quotes in the entityString. We don't need to do that,
          // since the .toJson below handles escaping internally.
          resp.withEntity(HttpEntity(ContentTypes.`application/json`, ErrorReport(statusCode, entityString).toJson.prettyPrint))
      }
    }
  }

  /*
    N.B. This file previously contained two rejection handlers. The second was specific for
    MalformedRequestContentRejection and produced almost exactly the same result as defaultErrorReportRejectionHandler
    above (minor toString differences in the error message itself). I have removed that extraneous handler
    to simplify routing and debugging.
   */

}
