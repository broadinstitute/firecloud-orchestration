package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.EntityCopyDefinition
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpResponse._
import org.mockserver.model.{HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.json._

class ValidEntityCopyCallback extends ExpectationCallback {

  override def handle(httpRequest: HttpRequest): HttpResponse = {

    val copyRequest = httpRequest.getBodyAsString.parseJson.convertTo[EntityCopyDefinition]

    (copyRequest.sourceWorkspace.namespace, copyRequest.destinationWorkspace.name) match {
      case (Some(x), Some(y)) if x == "broad-dsde-dev" && y == "valid" =>
        response()
          .withHeaders(header)
          .withStatusCode(Created.intValue)
      case _ =>
        response()
          .withHeaders(header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
    }
  }
}