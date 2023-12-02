package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.rawls.model.EntityCopyDefinition
import org.mockserver.mock.action.ExpectationResponseCallback
import org.mockserver.model.HttpResponse._
import org.mockserver.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import spray.json._

class ValidEntityCopyCallback extends ExpectationResponseCallback {

  override def handle(httpRequest: HttpRequest): HttpResponse = {

    val copyRequest = httpRequest.getBodyAsString.parseJson.convertTo[EntityCopyDefinition]

    (copyRequest.sourceWorkspace.namespace, copyRequest.destinationWorkspace.name) match {
      case (x:String, y:String) if x == "broad-dsde-dev" && y == "valid" =>
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
