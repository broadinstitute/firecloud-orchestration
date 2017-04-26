package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.HttpAgoraDAO
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, SystemStatus}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impSystemStatus
import org.broadinstitute.dsde.firecloud.webservice.StatusApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpError
import org.mockserver.model.HttpRequest.request
import spray.http.StatusCodes.{InternalServerError, OK}
import spray.routing.HttpService
import spray.httpx.SprayJsonSupport._


/**
  * Created by anichols on 4/13/17.
  */
class StatusServiceSpecMockServer extends BaseServiceSpec with HttpService with StatusApiService {

  def actorRefFactory = system

  // Typically, the BaseServiceSpec provides an `app: Application` member that has all Mock DAOs.
  // Here, we want to send requests through the Agora DAO to the Mock Server, so create a new Application
  // with the HTTP DAO instead of Mock.
  val customApp = new Application(new HttpAgoraDAO(FireCloudConfig.Agora), googleServicesDao, ontologyDao, rawlsDao, searchDao, thurloeDao)
  val statusServiceConstructor: () => StatusService = StatusService.constructor(customApp)

  var agoraServer: ClientAndServer   = startClientAndServer(MockUtils.methodsServerPort) // Agora = methods

  override def afterAll(): Unit = {
    agoraServer.stop()
  }

  agoraServer.when(
    request().withMethod("GET").withPath("/status")
  ).respond(
    org.mockserver.model.HttpResponse.response()
      .withHeaders(MockUtils.header)
      .withBody("""{ "status": "down", "message": ["Agora is down"] }""")
  )

  "StatusService indicates Agora failure when \"down\" response" in {
    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val response = responseAs[SystemStatus]
      response.ok should be(false)
      response.systems("Agora").ok should be(false)
      response.systems("Agora").messages should be(Some(List("Agora is down")))
      response.systems.size should be(4)
    }
  }

  agoraServer.when(
    request().withMethod("GET").withPath("/status")
  ).respond(
    org.mockserver.model.HttpResponse.response()
      .withHeaders(MockUtils.header)
      .withBody("""{ "status": "up", "message": ["No problems with Agora"] }""")
  )

  "StatusService indicates OK" in {
    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status.intValue should be(OK.intValue)
      val response = responseAs[SystemStatus]
      response.ok should be(true)
      response.systems("Agora").ok should be(true)
      response.systems("Agora").messages should be(None)
      response.systems.size should be(4)
    }
  }

  agoraServer.when(
    request().withMethod("GET").withPath("/status")
  ).respond(
    org.mockserver.model.HttpResponse.response()
      .withHeaders(MockUtils.header)
      .withBody("bogus non-JSON response")
  )

  "StatusService indicates failure with non-JSON response" in {
    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val response = responseAs[SystemStatus]
      response.ok should be(false)
      response.systems("Agora").ok should be(false)
      response.systems("Agora").messages should be(Some(List("bogus non-JSON response")))
      response.systems.size should be(4)
    }
  }

  agoraServer.when(
    request().withMethod("GET").withPath("/status")
  ).respond(
    org.mockserver.model.HttpResponse.response()
      .withHeaders(MockUtils.header)
      .withBody("")
  )

  "StatusService indicates failure with empty response" in {
    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val response = responseAs[SystemStatus]
      response.ok should be(false)
      response.systems("Agora").ok should be(false)
      response.systems("Agora").messages should be(Some(List("")))
      response.systems.size should be(4)
    }
  }

  agoraServer.when(
    request().withMethod("GET").withPath("/status")
  ).error((new HttpError).withDropConnection(true))

  "StatusService reports failure when Agora drops connection" in {
    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val response = responseAs[SystemStatus]
      response.ok should be(false)
      response.systems("Agora").ok should be(false)
      response.systems("Agora").messages should be(Some(List("Premature connection close (the server doesn't appear to support request pipelining)")))
      response.systems.size should be(4)
    }
  }
}

class StatusServiceSpecMockDAOs extends BaseServiceSpec with HttpService with StatusApiService {

  def actorRefFactory = system

  val statusServiceConstructor: () => StatusService = StatusService.constructor(app)

  "StatusService carries on despite exception in Agora Mock DAO" in {
    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status.intValue should be(500)
      val response = responseAs[SystemStatus]
      response.ok should be(false)
      response.systems("Agora").ok should be(false)
      response.systems("Thurloe").ok should be(true)
      response.systems("Rawls").ok should be(true)
      response.systems("Search").ok should be(true)
      response.systems("Agora").messages should be(Some(List("Agora Mock DAO exception")))
      response.systems.size should be(4)
    }

  }
}
