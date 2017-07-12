package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, SystemStatus}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impSystemStatus
import org.broadinstitute.dsde.firecloud.webservice.StatusApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpError
import org.mockserver.model.HttpRequest.request
import org.scalatest.BeforeAndAfterEach
import spray.http.StatusCodes.{InternalServerError, OK}
import spray.routing.HttpService
import spray.httpx.SprayJsonSupport._


/**
  * Created by anichols on 4/13/17.
  */
class StatusApiServiceSpecMockServer extends BaseServiceSpec with HttpService with StatusApiService with BeforeAndAfterEach {

  def actorRefFactory = system

  // Typically, the BaseServiceSpec provides an `app: Application` member that has all Mock DAOs.
  // Here, we want to send requests through the Agora DAO to the Mock Server, so create a new Application
  // with the HTTP DAO instead of Mock.
  val customApp = new Application(new HttpAgoraDAO(FireCloudConfig.Agora), googleServicesDao, ontologyDao, consentDao, rawlsDao, searchDao, thurloeDao)
  val statusServiceConstructor: () => StatusService = StatusService.constructor(customApp)

  var agoraServer: ClientAndServer   = startClientAndServer(MockUtils.methodsServerPort) // Agora = methods

  override def afterAll(): Unit = {
    agoraServer.stop()
  }

  override def beforeEach(): Unit = {
    agoraServer.reset()
  }

  "StatusService indicates Agora failure when \"down\" response" in {

    agoraServer.when(
      request().withMethod("GET").withPath("/status")
    ).respond(
      org.mockserver.model.HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withBody("""{ "status": "down", "message": ["Agora is down"] }""")
    )

    Get("/status") ~> sealRoute(statusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val statusResponse = responseAs[SystemStatus]
      statusResponse.ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).ok should be(false)
      statusResponse.systems(ThurloeDAO.serviceName).ok should be(true)
      statusResponse.systems(RawlsDAO.serviceName).ok should be(true)
      statusResponse.systems(SearchDAO.serviceName).ok should be(true)
      statusResponse.systems(AgoraDAO.serviceName).messages should be(Some(List("Agora is down")))
      statusResponse.systems.size should be(4)
    }
  }

  "StatusService indicates OK" in {

    agoraServer.when(
      request().withMethod("GET").withPath("/status")
    ).respond(
      org.mockserver.model.HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withBody("""{ "status": "up", "message": ["No problems with Agora"] }""")
    )

    Get("/status") ~> sealRoute(statusRoutes) ~> check {
      status.intValue should be(OK.intValue)
      val statusResponse = responseAs[SystemStatus]
      statusResponse.ok should be(true)
      statusResponse.systems(AgoraDAO.serviceName).ok should be(true)
      statusResponse.systems(AgoraDAO.serviceName).messages should be(None)
      statusResponse.systems.size should be(4)
    }
  }

  "StatusService indicates failure with non-JSON response" in {

    agoraServer.when(
      request().withMethod("GET").withPath("/status")
    ).respond(
      org.mockserver.model.HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withBody("bogus non-JSON response")
    )

    Get("/status") ~> sealRoute(statusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val statusResponse = responseAs[SystemStatus]
      statusResponse.ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).messages should be(Some(List("bogus non-JSON response")))
      statusResponse.systems.size should be(4)
    }
  }

  "StatusService indicates failure with empty response" in {

    agoraServer.when(
      request().withMethod("GET").withPath("/status")
    ).respond(
      org.mockserver.model.HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withBody("")
    )

    Get("/status") ~> sealRoute(statusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val statusResponse = responseAs[SystemStatus]
      statusResponse.ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).messages should be(Some(List("")))
      statusResponse.systems.size should be(4)
    }
  }

  "StatusService reports failure when Agora drops connection" in {

    agoraServer.when(
      request().withMethod("GET").withPath("/status")
    ).error((new HttpError).withDropConnection(true))

    Get("/status") ~> sealRoute(statusRoutes) ~> check {
      status.intValue should be(InternalServerError.intValue)
      val statusResponse = responseAs[SystemStatus]
      statusResponse.ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).messages.get.nonEmpty should be(true)
      statusResponse.systems.size should be(4)
    }
  }
}

class StatusApiServiceSpecMockDAOs extends BaseServiceSpec with HttpService with StatusApiService {

  def actorRefFactory = system

  val statusServiceConstructor: () => StatusService = StatusService.constructor(app)

  "StatusService carries on despite exception in Agora Mock DAO" in {
    Get("/status") ~> sealRoute(statusRoutes) ~> check {
      status.intValue should be(500)
      val statusResponse = responseAs[SystemStatus]
      statusResponse.ok should be(false)
      statusResponse.systems(AgoraDAO.serviceName).ok should be(false)
      statusResponse.systems(ThurloeDAO.serviceName).ok should be(true)
      statusResponse.systems(RawlsDAO.serviceName).ok should be(true)
      statusResponse.systems(SearchDAO.serviceName).ok should be(true)
      statusResponse.systems(AgoraDAO.serviceName).messages should be(Some(List("Agora Mock DAO exception")))
      statusResponse.systems.size should be(4)
    }

  }
}
