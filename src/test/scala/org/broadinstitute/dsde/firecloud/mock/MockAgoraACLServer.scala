package org.broadinstitute.dsde.firecloud.mock

import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiServiceUrls
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpCallback._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse._
import spray.http.StatusCodes._
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impAgoraPermission, impEntityAccessControlAgora}
import DefaultJsonProtocol._

object MockAgoraACLServer extends MethodsApiServiceUrls {

  val methodsServerPort = 8989

  val methodsUrl = remoteMethodsPath
  val configsUrl = remoteConfigurationsPath


  val standardPermsPath = "/ns/standard/1/permissions"
  val withEdgeCasesPath = "/ns/edges/1/permissions"

  val multiPermissionsPath = "/permissions"

  /****** Mock Data ******/

  import MockAgoraACLData.{edgesAgora, standardAgora, multiUpsertResponse}

  /****** Server ******/

  var methodsServer: ClientAndServer = _

  def stopACLServer(): Unit = {
    methodsServer.stop()
  }

  def startACLServer(): Unit = {

    methodsServer = startClientAndServer(methodsServerPort)

    // configuration endpoints return the mock data in the proper order
    MockAgoraACLServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath(configsUrl + standardPermsPath))
      .respond(
      response()
          .withHeader(header)
          .withBody(standardAgora.toJson.compactPrint)
        .withStatusCode(OK.intValue)
      )

    MockAgoraACLServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath(configsUrl + withEdgeCasesPath))
      .respond(
        response()
          .withHeader(header)
          .withBody(edgesAgora.toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    // methods endpoints return the mock data in reverse order - this way we can differentiate methods vs. configs
    MockAgoraACLServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath(methodsUrl + standardPermsPath))
      .respond(
        response()
          .withHeader(header)
          .withBody(standardAgora.reverse.toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    MockAgoraACLServer.methodsServer
      .when(
        request()
          .withMethod("GET")
          .withPath(methodsUrl + withEdgeCasesPath))
      .respond(
        response()
          .withHeader(header)
          .withBody(edgesAgora.reverse.toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    // POSTS
    // configs returns a good, parsable response from Agora
    MockAgoraACLServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath(configsUrl + standardPermsPath))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidAgoraACLCallback")
      )

    // methods returns a bad, unparsable response from Agora
    MockAgoraACLServer.methodsServer
      .when(
        request()
          .withMethod("POST")
          .withPath(methodsUrl + standardPermsPath))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.InvalidAgoraACLCallback")
      )

    // multi-permissions endpoint; currently hardcoded to return empty array because we don't really care
    MockAgoraACLServer.methodsServer
      .when(
        request()
          .withMethod("PUT")
          .withPath(methodsUrl + multiPermissionsPath))
      .respond(
        response()
          .withHeader(header)
          .withBody(multiUpsertResponse.toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

  }
}
