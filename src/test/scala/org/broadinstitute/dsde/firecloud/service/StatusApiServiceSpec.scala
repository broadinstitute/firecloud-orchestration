package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.{MockGoogleServicesDAO, MockUtils}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impSystemStatus
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, SystemStatus}
import org.broadinstitute.dsde.firecloud.webservice.StatusApiService
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.{HttpRequest, HttpResponse}
import org.scalatest.BeforeAndAfter
import spray.http.StatusCodes.{InternalServerError, OK}
import spray.httpx.SprayJsonSupport._
import spray.routing.HttpService

import scala.concurrent.{ExecutionContext, Future}

object StatusApiServiceSpec {
  val numberOfStatusServices = 7
}

class MockGoogleServicesUpDAO(implicit val executionContext: ExecutionContext) extends MockGoogleServicesDAO {
  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, messages = None))
}

class MockGoogleServicesDownDAO(implicit val executionContext: ExecutionContext) extends MockGoogleServicesDAO {
  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = false, messages = Some(List("Google is broken"))))
}

class MockSearchUpDAO(implicit val executionContext: ExecutionContext) extends MockSearchDAO {
  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true))
}

class MockSearchDownDAO(implicit val executionContext: ExecutionContext) extends MockSearchDAO {
  override def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = false, messages = Some(List("ES is broken"))))
}

trait StatusApiServiceSpecSupport {

  implicit val system: ActorSystem
  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    * Most DAOs can be mocked via mock-server since they make http calls to their respective "/status" endpoints.
    * Google uses the google client to check for project access, so instead we mock out two versions
    * of the DAOs, one for positive tests, one for negative/mixed tests.
    * Similarly, ES uses a transport client, not the rest client, so we don't mock those either.
    */
  val googleServicesUpDAO:GoogleServicesDAO = new MockGoogleServicesUpDAO()
  val googleServicesDownDAO:GoogleServicesDAO = new MockGoogleServicesDownDAO()
  val searchUpDAO:SearchDAO = new MockSearchUpDAO()
  val searchDownDAO:SearchDAO = new MockSearchDownDAO()
  val agoraDAO:AgoraDAO = new HttpAgoraDAO(FireCloudConfig.Agora)
  val rawlsDAO:RawlsDAO = new HttpRawlsDAO
  val thurloeDAO:ThurloeDAO = new HttpThurloeDAO
  val ontologyDAO:OntologyDAO = new HttpOntologyDAO
  val consentDAO:ConsentDAO = new HttpConsentDAO

  // No need for a mocked google or search servers/responses
  var agoraServer: ClientAndServer = _
  var rawlsServer: ClientAndServer = _
  var thurloeServer: ClientAndServer = _
  var ontologyServer: ClientAndServer = _
  var consentServer: ClientAndServer = _

  def startAll(): Unit = {
    agoraServer = startClientAndServer(MockUtils.methodsServerPort)
    rawlsServer = startClientAndServer(MockUtils.workspaceServerPort)
    thurloeServer = startClientAndServer(MockUtils.thurloeServerPort)
    ontologyServer = startClientAndServer(MockUtils.ontologyServerPort)
    consentServer = startClientAndServer(MockUtils.consentServerPort)
  }

  def resetAll(): Unit = {
    agoraServer.reset()
    rawlsServer.reset()
    thurloeServer.reset()
    ontologyServer.reset()
    consentServer.reset()
  }

  def stopAll(): Unit = {
    agoraServer.stop()
    rawlsServer.stop()
    thurloeServer.stop()
    ontologyServer.stop()
    consentServer.stop()
  }

  val statusRequest: HttpRequest = request().withMethod("GET").withPath("/status")

  val agoraDown: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{ "status": "down", "message": ["Agora is down"] }""")
  val rawlsDown: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{ "ok": false, "systems": {"GooglePubSub": {"ok": false, "message": ["PubSub is broken"]}, "GoogleGenomics": {"ok": true}, "LDAP": {"ok": true}, "Database": {"ok": true}, "Agora": {"ok": true}, "GoogleGroups": {"ok": true}, "GoogleBilling": {"ok": true}, "Cromwell": {"ok": true}, "GoogleBuckets": {"ok": true}}}""")
  val thurloeDown: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{ "status": "down", "error": "Thurloe is down" }""")
  val ontologyDown: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{"deadlocks":{"healthy":false},"elastic-search":{"healthy":false,"message":"ClusterHealth is RED"},"google-cloud-storage":{"healthy":true}}""")
  val consentDown: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{"deadlocks":{"healthy":false},"elastic-search":{"healthy":false,"message":"ClusterHealth is RED"},"google-cloud-storage":{"healthy":true},"mongodb":{"healthy":true},"mysql":{"healthy":true}}""")

  def mockAllDown(): Unit = {
    resetAll()
    agoraServer.when(statusRequest).respond(agoraDown)
    rawlsServer.when(statusRequest).respond(rawlsDown)
    thurloeServer.when(statusRequest).respond(thurloeDown)
    ontologyServer.when(statusRequest).respond(ontologyDown)
    consentServer.when(statusRequest).respond(consentDown)
  }

  val agoraUp: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{ "status": "up", "message": [] }""")
  val rawlsUp: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{ "ok": true, "systems": {"GooglePubSub": {"ok": true}, "GoogleGenomics": {"ok": true}, "LDAP": {"ok": true}, "Database": {"ok": true}, "Agora": {"ok": true}, "GoogleGroups": {"ok": true}, "GoogleBilling": {"ok": true}, "Cromwell": {"ok": true}, "GoogleBuckets": {"ok": true}}}""")
  val thurloeUp: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{ "status": "up" }""")
  val ontologyUp: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{"deadlocks":{"healthy":true},"elastic-search":{"healthy":true,"message":"ClusterHealth is GREEN"},"google-cloud-storage":{"healthy":true}}""")
  val consentUp: HttpResponse = response().withHeaders(MockUtils.header).withStatusCode(200).withBody("""{"deadlocks":{"healthy":true},"elastic-search":{"healthy":true,"message":"ClusterHealth is GREEN"},"google-cloud-storage":{"healthy":true},"mongodb":{"healthy":true},"mysql":{"healthy":true}}""")

  def mockAllUp(): Unit = {
    resetAll()
    agoraServer.when(statusRequest).respond(agoraUp)
    rawlsServer.when(statusRequest).respond(rawlsUp)
    thurloeServer.when(statusRequest).respond(thurloeUp)
    ontologyServer.when(statusRequest).respond(ontologyUp)
    consentServer.when(statusRequest).respond(consentUp)
  }

  def mockSomeUp(): Unit = {
    resetAll()
    agoraServer.when(statusRequest).respond(agoraDown)
    rawlsServer.when(statusRequest).respond(rawlsUp)
    thurloeServer.when(statusRequest).respond(thurloeUp)
    ontologyServer.when(statusRequest).respond(ontologyDown)
    consentServer.when(statusRequest).respond(consentUp)
  }

  // TODO: Mock some external server exceptions to ensure that the status endpoint still works

}

class StatusApiServicePositiveSpec extends BaseServiceSpec with HttpService with StatusApiService with BeforeAndAfter with StatusApiServiceSpecSupport {

  def actorRefFactory: ActorSystem = system

  // Typically, the BaseServiceSpec provides an `app: Application` member that has all Mock DAOs.
  // Here, we want to send requests through the Agora DAO to the Mock Server, so create a new Application
  // with the HTTP DAO instead of Mock.
  val customApp = Application(agoraDAO, googleServicesUpDAO, ontologyDAO, consentDAO, rawlsDAO, searchUpDAO, thurloeDAO)
  val statusServiceConstructor: () => StatusService = StatusService.constructor(customApp)

  override def beforeAll(): Unit = startAll()

  override def afterAll(): Unit = stopAll()

  "StatusApiServicePositiveSpec" - {
    "When all services are functional" - {
      "StatusService indicates OK" in {
        mockAllUp()
        Get("/status") ~> sealRoute(statusRoutes) ~> check {
          status.intValue should be(OK.intValue)
          val statusResponse = responseAs[SystemStatus]
          statusResponse.ok should be(true)
          statusResponse.systems(GoogleServicesDAO.serviceName).ok should be(true)
          statusResponse.systems(AgoraDAO.serviceName).ok should be(true)
          statusResponse.systems(ThurloeDAO.serviceName).ok should be(true)
          statusResponse.systems(RawlsDAO.serviceName).ok should be(true)
          statusResponse.systems(SearchDAO.serviceName).ok should be(true)
          statusResponse.systems(OntologyDAO.serviceName).ok should be(true)
          statusResponse.systems(ConsentDAO.serviceName).ok should be(true)
          statusResponse.systems.size should be(StatusApiServiceSpec.numberOfStatusServices)
        }
      }
    }
  }
}

class StatusApiServiceNegativeSpec extends BaseServiceSpec with HttpService with StatusApiService with StatusApiServiceSpecSupport {

  def actorRefFactory: ActorSystem = system

  // Typically, the BaseServiceSpec provides an `app: Application` member that has all Mock DAOs.
  // Here, we want to send requests through the Agora DAO to the Mock Server, so create a new Application
  // with the HTTP DAO instead of Mock.
  val customApp = Application(agoraDAO, googleServicesDownDAO, ontologyDAO, consentDAO, rawlsDAO, searchDownDAO, thurloeDAO)
  val statusServiceConstructor: () => StatusService = StatusService.constructor(customApp)

  override def beforeAll(): Unit = startAll()

  override def afterAll(): Unit = stopAll()

  "StatusApiServiceNegativeSpec" - {

    "When all services are nonfunctional" - {
      "StatusService indicates failures" in {
        mockAllDown()
        Get("/status") ~> sealRoute(statusRoutes) ~> check {
          status.intValue should be(InternalServerError.intValue)
          val statusResponse = responseAs[SystemStatus]
          statusResponse.ok should be(false)
          statusResponse.systems(GoogleServicesDAO.serviceName).ok should be(false)
          statusResponse.systems(AgoraDAO.serviceName).ok should be(false)
          statusResponse.systems(ThurloeDAO.serviceName).ok should be(false)
          statusResponse.systems(RawlsDAO.serviceName).ok should be(false)
          statusResponse.systems(SearchDAO.serviceName).ok should be(false)
          statusResponse.systems(OntologyDAO.serviceName).ok should be(false)
          statusResponse.systems(ConsentDAO.serviceName).ok should be(false)
          statusResponse.systems.size should be(StatusApiServiceSpec.numberOfStatusServices)
        }
      }
    }

    "When some services are nonfunctional and some are functional" - {
      "StatusService indicates both successes and failures" in {
        mockSomeUp()
        Get("/status") ~> sealRoute(statusRoutes) ~> check {
          status.intValue should be(InternalServerError.intValue)
          val statusResponse = responseAs[SystemStatus]
          println(statusResponse)
          statusResponse.ok should be(false)
          statusResponse.systems(GoogleServicesDAO.serviceName).ok should be(false)
          statusResponse.systems(AgoraDAO.serviceName).ok should be(false)
          statusResponse.systems(ThurloeDAO.serviceName).ok should be(true)
          statusResponse.systems(RawlsDAO.serviceName).ok should be(true)
          statusResponse.systems(SearchDAO.serviceName).ok should be(false)
          statusResponse.systems(OntologyDAO.serviceName).ok should be(false)
          statusResponse.systems(ConsentDAO.serviceName).ok should be(true)
          statusResponse.systems.size should be(StatusApiServiceSpec.numberOfStatusServices)
        }
      }

    }
  }
}
