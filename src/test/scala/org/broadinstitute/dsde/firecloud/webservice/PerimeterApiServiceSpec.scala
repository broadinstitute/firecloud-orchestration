package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse

import scala.concurrent.ExecutionContext

final class PerimeterApiServiceSpec extends BaseServiceSpec with PerimeterApiService {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val validPerimeter = "foo"
  val invalidPerimeter = "missing"

  val validProject = "bar"
  val invalidProject = "firehose"
  val notReadyProject = "unready"

  var mockWorkspaceServer: ClientAndServer = _

  override def beforeAll(): Unit = {
    val perimeterPath = FireCloudConfig.Rawls.authPrefix + "/servicePerimeters"

    mockWorkspaceServer = startClientAndServer(MockUtils.workspaceServerPort)

    mockWorkspaceServer.when(
      request()
        .withMethod(PUT.name)
        .withPath(s"$perimeterPath/$validPerimeter/projects/$validProject"))
      .respond(HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(Accepted.intValue))

    mockWorkspaceServer.when(
      request()
        .withMethod(PUT.name)
        .withPath(s"$perimeterPath/$invalidPerimeter/projects/$validProject"))
      .respond(HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withStatusCode(NotFound.intValue))

    mockWorkspaceServer.when(
      request()
        .withMethod(PUT.name)
        .withPath(s"$perimeterPath/$validPerimeter/projects/$invalidProject"))
      .respond(HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withStatusCode(Forbidden.intValue))

    mockWorkspaceServer.when(
      request()
        .withMethod(PUT.name)
        .withPath(s"$perimeterPath/$validPerimeter/projects/$notReadyProject"))
      .respond(HttpResponse.response()
        .withHeaders(MockUtils.header)
        .withStatusCode(BadRequest.intValue))

  }

  override def afterAll(): Unit = {
    mockWorkspaceServer.stop()
  }

  "PerimeterApiService" - {
    "add project to perimeter" in {
      Put(s"/servicePerimeters/$validPerimeter/projects/$validProject") ~> dummyAuthHeaders ~> sealRoute(perimeterServiceRoutes) ~> check {
        status should be(Accepted)
      }
    }

    "add project to invalid perimeter" in {
      Put(s"/servicePerimeters/$invalidPerimeter/projects/$validProject") ~> dummyAuthHeaders ~> sealRoute(perimeterServiceRoutes) ~> check {
        status should be(NotFound)
      }
    }

    "add invalid project to perimeter" in {
      Put(s"/servicePerimeters/$validPerimeter/projects/$invalidProject") ~> dummyAuthHeaders ~> sealRoute(perimeterServiceRoutes) ~> check {
        status should be(Forbidden)
      }
    }

    "add unready project to perimeter" in {
      Put(s"/servicePerimeters/$validPerimeter/projects/$notReadyProject") ~> dummyAuthHeaders ~> sealRoute(perimeterServiceRoutes) ~> check {
        status should be(BadRequest)
      }
    }

  }
}
