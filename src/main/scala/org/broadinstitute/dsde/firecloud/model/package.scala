package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.marshalling
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler}

import scala.language.implicitConversions

package object model {
  implicit val errorReportSource = ErrorReportSource("FireCloud")

  import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
  import akka.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}

//  implicit val errorReportRejectionHandler = RejectionHandler.newBuilder().handle {
//    case MalformedRequestContentRejection(errorMsg, _) =>
//      complete { (StatusCodes.BadRequest, ErrorReport(StatusCodes.BadRequest, errorMsg)) }
//    case _ => RejectionHandler.default.mapRejectionResponse {
//      case resp@HttpResponse(statusCode, _, ent: HttpEntity.Strict, _) => {
//        // since all Akka default rejection responses are Strict this will handle all rejections
//        val message = ent.data.utf8String.replaceAll("\"", """\"""")
//
//        //TODO: wrap this message in an ErrorReport a la ErrorReport(statusCode, message))
//        resp.withEntity(HttpEntity(ContentTypes.`application/json`, message))
//      }
//    }
//  }

}
