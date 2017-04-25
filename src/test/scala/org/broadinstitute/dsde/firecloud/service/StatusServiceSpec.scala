package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, HttpAgoraDAO}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils.authHeader
import org.broadinstitute.dsde.firecloud.model.DUOS.{Consent, ConsentError}
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, SystemStatus}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impSystemStatus
import org.broadinstitute.dsde.firecloud.webservice.StatusApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import spray.http.StatusCodes.{BadRequest, InternalServerError, NotFound, NotImplemented, OK}
import spray.routing.HttpService
import spray.httpx.SprayJsonSupport._


/**
  * Created by anichols on 4/13/17.
  */
class StatusServiceSpec extends BaseServiceSpec with HttpService with StatusApiService {

  def actorRefFactory = system

  // Typically, the BaseServiceSpec provides an `app: Application` member that has all Mock DAOs.
  // Here, we want to send requests through the Agora DAO to the Mock Server, so create a new Application
  // with the HTTP DAO instead of Mock.
  val customApp = new Application(new HttpAgoraDAO(FireCloudConfig.Agora), googleServicesDao, ontologyDao, rawlsDao, searchDao, thurloeDao)
  val statusServiceConstructor: () => StatusService = StatusService.constructor(customApp)

  var searchServer: ClientAndServer  = startClientAndServer(MockUtils.searchServerPort)
  var thurloeServer: ClientAndServer = startClientAndServer(MockUtils.thurloeServerPort)
  var rawlsServer: ClientAndServer   = startClientAndServer(MockUtils.workspaceServerPort) // Rawls = workspace
  var agoraServer: ClientAndServer   = startClientAndServer(MockUtils.methodsServerPort) // Agora = methods

  override def afterAll(): Unit = {
    Seq(searchServer, thurloeServer, rawlsServer, agoraServer).map(_.stop())
  }

  agoraServer.when(
    request().withMethod("GET").withPath("/status")
  ).respond(
    org.mockserver.model.HttpResponse.response()
      .withHeaders(MockUtils.header)
      .withBody("""{ "status": "down", "message": ["Agora is down"] }""")
  )

  "StatusService indicates Agora failure" in {
    Get("/status") ~> sealRoute(publicStatusRoutes) ~> check {
      status should be(InternalServerError)
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
      status should be(OK)
      val response = responseAs[SystemStatus]
      response.ok should be(true)
      response.systems("Agora").ok should be(true)
      response.systems("Agora").messages should be(None)
      response.systems.size should be(4)
    }

  }

}
