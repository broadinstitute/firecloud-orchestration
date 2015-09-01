package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.MethodConfigurationCopy
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpResponse._
import org.mockserver.model.{HttpRequest, HttpResponse}
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.json._

class ValidMethodConfigurationFromRepoCallback extends ExpectationCallback {

  lazy val log = LoggerFactory.getLogger(getClass)

  override def handle(httpRequest: HttpRequest): HttpResponse = {
    if (httpRequest.containsHeader("Authorization")) {
      log.debug(httpRequest.getHeaders.toString)
      val jsonAst = httpRequest.getBodyAsString.parseJson
      val config = jsonAst.convertTo[MethodConfigurationCopy]
      config match {
        case x if x.methodRepoName.isDefined
          && x.methodRepoNamespace.isDefined
          && x.methodRepoSnapshotId.isDefined
          && x.destination.isDefined
          && x.destination.get.name.isDefined
          && x.destination.get.namespace.isDefined
          && x.destination.get.workspaceName.isDefined
          && x.destination.get.workspaceName.get.name.isDefined
          && x.destination.get.workspaceName.get.namespace.isDefined =>
          log.debug("Match valid method configuration copy object")
          response()
            .withHeaders(header)
            .withStatusCode(Created.intValue)
        case _ =>
          log.debug("Match invalid method configuration copy object")
          response()
            .withHeaders(header)
            .withStatusCode(BadRequest.intValue)
      }
    } else {
      log.debug("No authentication header provided")
      response()
        .withHeaders(header)
        .withBody("Authentication is possible but has failed or not yet been provided.")
        .withStatusCode(Unauthorized.intValue)
    }
  }

}
