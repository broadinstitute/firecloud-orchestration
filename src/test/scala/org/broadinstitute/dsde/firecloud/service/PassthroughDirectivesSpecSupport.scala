package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.PassthroughDirectivesSpec._
import org.broadinstitute.dsde.firecloud.service.PassthroughDirectivesSpecSupport._
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.StatusCodes.OK
import spray.http.Uri
import spray.http.Uri.Path
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConversions._

class EchoCallback extends ExpectationCallback {
  override def handle(httpRequest: HttpRequest): HttpResponse = {
    // translate the mockserver request to a spray Uri
    val sprayparams = httpRequest.getQueryStringParameters.toSeq.map{p =>
      assert(p.getValues.size() <= 1)
      p.getName.getValue -> p.getValues.head.getValue}.toMap

    val sprayuri = Uri(echoUrl)
      .withPath(Path(httpRequest.getPath.getValue))
      .withQuery(sprayparams)

    val requestInfo = RequestInfo(
      httpRequest.getMethod.getValue,
      sprayuri.path.toString,
      sprayuri.query.toMap,
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
  implicit val requestInfoFormat = jsonFormat4(RequestInfo)
}
