package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.PassthroughDirectivesSpec._
import org.broadinstitute.dsde.firecloud.service.PassthroughDirectivesSpecSupport._
import org.mockserver.mock.action.ExpectationResponseCallback
import org.mockserver.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{Path, Query}
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

import scala.jdk.CollectionConverters._

class EchoCallback extends ExpectationResponseCallback {
  override def handle(httpRequest: HttpRequest): HttpResponse = {
    // translate the mockserver request to a spray Uri
    val query: Query = Option(httpRequest.getQueryStringParameters) match {
      case None => Query.Empty
      case Some(params) => Query(params.getRawParameterString)
    }

    val sprayuri = Uri(echoUrl)
      .withPath(Path(httpRequest.getPath.getValue))
      .withQuery(query)

    val requestInfo = RequestInfo(
      httpRequest.getMethod.getValue,
      sprayuri.path.toString,
      sprayuri.query().toMap,
      sprayuri.toString()
    )

    org.mockserver.model.HttpResponse.response()
      .withStatusCode(OK.intValue)
      .withHeader(MockUtils.header)
      .withBody(requestInfoFormat.write(requestInfo).prettyPrint)
  }
}

case class RequestInfo(
  method: String,
  path: String,
  queryparams: Map[String,String],
  url: String)

object PassthroughDirectivesSpecSupport {
  implicit val requestInfoFormat: RootJsonFormat[RequestInfo] = jsonFormat4(RequestInfo)
}
