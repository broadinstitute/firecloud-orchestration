package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.AgoraPermission
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpResponse._
import org.mockserver.model.{HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.json._

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impAgoraPermission
import spray.json.DefaultJsonProtocol._

// returns the standard mock data
class ValidAgoraACLCallback extends ExpectationCallback with AgoraACLCallback {
  override def handle(httpRequest: HttpRequest) = callbackHandle(httpRequest, MockAgoraACLData.standardAgora)
}

// returns the edge-case mock data
class InvalidAgoraACLCallback extends ExpectationCallback with AgoraACLCallback {
  override def handle(httpRequest: HttpRequest) = callbackHandle(httpRequest, MockAgoraACLData.edgesAgora)
}


trait AgoraACLCallback {
  def callbackHandle(httpRequest: HttpRequest, responseBody: List[AgoraPermission]): HttpResponse = {

    val jsonAst = httpRequest.getBodyAsString.parseJson
    val agoraPermissions = jsonAst.convertTo[List[AgoraPermission]]
    agoraPermissions match {
      case MockAgoraACLData.translatedStandardAgora =>
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
          .withBody(responseBody.toJson.prettyPrint)
      case x =>
        response()
          .withHeaders(header)
          .withStatusCode(Forbidden.intValue) // agora won't return forbidden; we use it here for testing only
          .withBody("Posted data did not match expected standardAgora value")
    }
  }
}
