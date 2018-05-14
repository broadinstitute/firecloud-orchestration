package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.{StatusCode => AkkaStatusCode}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport._
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import spray.http.MediaTypes.`text/plain`
import spray.http.{ContentType, HttpEntity, HttpResponse, StatusCode => SprayStatusCode, StatusCodes}
import spray.routing.{MalformedRequestContentRejection, RejectionHandler}
import spray.client.pipelining._
import spray.httpx.SprayJsonSupport._
import scala.language.implicitConversions

import scala.util.Try

package object model {
  implicit val errorReportSource = ErrorReportSource("FireCloud")

  implicit def spray2akkaStatus(spray: SprayStatusCode): AkkaStatusCode = {
    AkkaStatusCode.int2StatusCode(spray.intValue)
  }

  implicit def akka2sprayStatus(akka: AkkaStatusCode): SprayStatusCode = {
    SprayStatusCode.int2StatusCode(akka.intValue)
  }

  implicit def optAkka2sprayStatus(optAkka: Option[AkkaStatusCode]): Option[SprayStatusCode] = {
    optAkka.map(sc => SprayStatusCode.int2StatusCode(sc.intValue))
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
