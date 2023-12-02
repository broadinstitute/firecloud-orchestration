package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.EntityId
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json.DefaultJsonProtocol._
import org.mockserver.mock.action.ExpectationResponseCallback
import org.mockserver.model.HttpResponse._
import org.mockserver.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import spray.json._

import scala.util.Try

class ValidEntityDeleteCallback extends ExpectationResponseCallback {

  val validEntities = Set(EntityId("sample", "id"), EntityId("sample", "bar"))

  override def handle(httpRequest: HttpRequest): HttpResponse = {
    val deleteRequest = Try(httpRequest.getBodyAsString.parseJson.convertTo[Set[EntityId]])

    if (deleteRequest.isSuccess && deleteRequest.get.subsetOf(validEntities)) {
      response()
        .withHeaders(header)
        .withStatusCode(NoContent.intValue)
    }
    else {
      response()
        .withHeaders(header)
        .withStatusCode(BadRequest.intValue)
        .withBody(MockUtils.rawlsErrorReport(BadRequest).toJson.compactPrint)
    }
  }
}
