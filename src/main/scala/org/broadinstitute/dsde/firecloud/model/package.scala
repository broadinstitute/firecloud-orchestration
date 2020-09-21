package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
//import CustomDirectives._
import akka.http.scaladsl.marshalling
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler}

import scala.language.implicitConversions

package object model {
  implicit val errorReportSource = ErrorReportSource("FireCloud")

  // adapted from https://gist.github.com/jrudolph/9387700
  implicit val errorReportRejectionHandler = RejectionHandler {
    case MalformedRequestContentRejection(errorMsg, _) :: _ =>
      ctx => ctx.complete(StatusCodes.BadRequest, ErrorReport(StatusCodes.BadRequest, errorMsg))
    case x if RejectionHandler.Default.isDefinedAt(x) =>
      ctx => RejectionHandler.Default(x) {
        ctx.withHttpResponseMapped {
          case resp@HttpResponse(statusCode, HttpEntity.NonEmpty(ContentType(`text/plain`, _), msg), _, _) =>
//            import spray.httpx.marshalling
            resp.withEntity(marshalling.marshalUnsafe(ErrorReport(statusCode, msg.asString)))
        }
      }
  }

//  implicit val errorReportRejectionHandler = RejectionHandler.newBuilder().handle {
//    case MalformedRequestContentRejection(errorMsg, _) =>
//      complete { (StatusCodes.BadRequest, ErrorReport(StatusCodes.BadRequest, errorMsg)) }
//    case _ => RejectionHandler.default.mapRejectionResponse {
//      case resp@HttpResponse(statusCode, _, , _, _) =>
//        resp.withEntity(marshalling.ErrorReport(statusCode, "foo"))
//    }
//  }
//
//
//  implicit def errorReportRejectionHandler2 = RejectionHandler.default.mapRejectionResponse {
//    case MalformedRequestContentRejection(errorMsg, _) =>
//      null
//    case x if RejectionHandler.default
//  }

}
