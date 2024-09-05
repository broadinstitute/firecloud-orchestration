package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes.{MethodNotAllowed, OK}
import akka.http.scaladsl.model.{HttpMethod, HttpMethods, StatusCode}
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.ExecutionContext

class WorkspaceV2ApiServiceSpec extends BaseServiceSpec with WorkspaceV2ApiService with BeforeAndAfterEach with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  // Mock remote endpoints
  private final val workspacesV2Root = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesV2Path
  private final val workspacesV2Path = workspacesV2Root + "/%s/%s".format("namespace", "name")

  private final val settingsPath = workspacesV2Path + "/settings"

  val dummyUserId = "1234"

  var rawlsServer: ClientAndServer = _

  /** Stubs the mock Rawls service to respond to a request. Used for testing passthroughs.
   *
   * @param method HTTP method to respond to
   * @param path   request path
   * @param status status for the response
   */
  def stubRawlsService(method: HttpMethod, path: String, status: StatusCode, body: Option[String] = None, query: Option[(String, String)] = None, requestBody: Option[String] = None): Unit = {
    rawlsServer.reset()
    val request = org.mockserver.model.HttpRequest.request()
      .withMethod(method.name)
      .withPath(path)
    if (query.isDefined) request.withQueryStringParameter(query.get._1, query.get._2)
    requestBody.foreach(request.withBody)
    val response = org.mockserver.model.HttpResponse.response()
      .withHeaders(MockUtils.header).withStatusCode(status.intValue)
    if (body.isDefined) response.withBody(body.get)
    rawlsServer
      .when(request)
      .respond(response)
  }

  override def beforeAll(): Unit = {
    rawlsServer = startClientAndServer(MockUtils.workspaceServerPort)
  }

  override def afterAll(): Unit = {
    rawlsServer.stop
  }

  "WorkspaceService Passthrough Tests" - {

    "Passthrough tests on the /workspaces/v2/segment/segment/settings path" - {
      s"OK status is returned for HTTP GET if Rawls returns it" in {
        stubRawlsService(HttpMethods.GET, settingsPath, OK)
        Get(settingsPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceV2Routes) ~> check {
          status should equal(OK)
        }
      }

      s"OK status is returned for HTTP PUT if Rawls returns it" in {
        stubRawlsService(HttpMethods.PUT, settingsPath, OK)
        Put(settingsPath) ~> dummyUserIdHeaders(dummyUserId) ~> sealRoute(workspaceV2Routes) ~> check {
          status should equal(OK)
        }
      }
    }
  }
}