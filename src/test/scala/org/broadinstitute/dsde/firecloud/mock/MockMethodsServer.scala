package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ErrorReport
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{Configuration, Method}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse._
import spray.http.StatusCode
import spray.http.StatusCodes._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.json._
import DefaultJsonProtocol._

object MockMethodsServer {

  val methodsServerPort = 8989

  /****** Mock Data ******/

  val mockMethods: List[Method] = {
    List.tabulate(randomPositiveInt())(
      n =>
        Method(
          namespace = Some(randomAlpha()),
          name = Some(randomAlpha()),
          snapshotId = Some(randomPositiveInt()),
          synopsis = Some(randomAlpha()),
          owner = Some(randomAlpha()),
          createDate = Some(isoDate()),
          url = Some(randomAlpha()),
          entityType = Some(randomAlpha())
        )
    )
  }
  val mockConfigurations: List[Configuration] = {
    List.tabulate(randomPositiveInt())(
      n =>
        Configuration(
          namespace = Some(randomAlpha()),
          name = Some(randomAlpha()),
          snapshotId = Some(randomPositiveInt()),
          synopsis = Some(randomAlpha()),
          documentation = Some(randomAlpha()),
          owner = Some(randomAlpha()),
          payload = Some(randomAlpha()),
          excludedField = Some(randomAlpha()),
          includedField = Some(randomAlpha())
        )
    )
  }

  def agoraErrorReport(statusCode: StatusCode) =
    ErrorReport("Agora", "dummy text", Option(statusCode), Seq(), Seq())

  /****** Server ******/

  var methodsServer: ClientAndServer = _

  def stopMethodsServer(): Unit = {
    methodsServer.stop()
  }

  def startMethodsServer(): Unit = {

    methodsServer = startClientAndServer(methodsServerPort)

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods")
          .withHeader(authHeader)
      ).respond(
        response()
          .withHeaders(header)
          .withBody(
            mockMethods.toJson.prettyPrint
          )
          .withStatusCode(OK.intValue)
      )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/methods")
      ).respond(
        response()
          .withHeaders(header)
          .withBody("Invalid authentication token, please log in.")
          .withStatusCode(Found.intValue)
      )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/methods")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("PUT")
          .withPath("/methods")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/configurations")
          .withHeader(authHeader)
      ).respond(
        response()
          .withHeaders(header)
          .withBody(
            mockConfigurations.toJson.prettyPrint
          )
          .withStatusCode(OK.intValue)
      )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath("/configurations")
      ).respond(
        response()
          .withHeaders(header)
          .withBody("Invalid authentication token, please log in.")
          .withStatusCode(Found.intValue)
      )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath("/configurations")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

    MockMethodsServer.methodsServer
      .when(
        request()
          .withMethod("PUT")
          .withPath("/configurations")
      ).respond(
      response()
        .withStatusCode(MethodNotAllowed.intValue)
        .withHeader(header)
        .withBody(agoraErrorReport(MethodNotAllowed).toJson.compactPrint)
    )

  }

}
