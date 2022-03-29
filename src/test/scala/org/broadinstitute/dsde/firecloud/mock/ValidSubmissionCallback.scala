package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.OrchSubmissionRequest
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpResponse._
import org.mockserver.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import spray.json._

class ValidSubmissionCallback extends ExpectationCallback {

  override def handle(httpRequest: HttpRequest): HttpResponse = {

    val jsonAst = httpRequest.getBodyAsString.parseJson
    val submission = jsonAst.convertTo[OrchSubmissionRequest]
    submission match {
      case x if x.entityName.isDefined &&
        x.entityType.isDefined &&
        x.expression.isDefined &&
        x.useCallCache.isDefined &&
        x.deleteIntermediateOutputFiles.isDefined &&
        x.workflowFailureMode.isDefined &&
        x.methodConfigurationName.isDefined &&
        x.methodConfigurationNamespace.isDefined =>
        response()
          .withHeaders(header)
          .withStatusCode(OK.intValue)
          .withBody(MockWorkspaceServer.mockValidSubmission.toJson.prettyPrint)
      case _ =>
        response()
          .withHeaders(header)
          .withStatusCode(BadRequest.intValue)
          .withBody(MockUtils.rawlsErrorReport(BadRequest).toJson.compactPrint)
    }

  }

}
