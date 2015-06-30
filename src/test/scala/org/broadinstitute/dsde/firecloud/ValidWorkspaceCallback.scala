package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.WorkspaceIngest
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpResponse._
import org.mockserver.model.{HttpRequest, HttpResponse}
import spray.http.StatusCodes._
import spray.json._

class ValidWorkspaceCallback extends ExpectationCallback {

  override def handle(httpRequest: HttpRequest): HttpResponse = {

    val jsonAst = httpRequest.getBodyAsString.parseJson
    val workspace = jsonAst.convertTo[WorkspaceIngest]
    workspace match {
      case x if x.name.isDefined && x.namespace.isDefined =>
        response()
          .withHeaders(MockServers.header)
          .withStatusCode(Created.intValue)
          .withBody(MockServers.createMockWorkspace().toJson.prettyPrint)
      case _ =>
        response()
          .withHeaders(MockServers.header)
          .withStatusCode(BadRequest.intValue)
    }

  }

}
