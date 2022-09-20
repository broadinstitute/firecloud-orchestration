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

final class BillingApiServiceSpec extends BaseServiceSpec with BillingApiService {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  var workspaceServer: ClientAndServer = _

  override def beforeAll(): Unit = {
    val billingPath = FireCloudConfig.Rawls.authPrefix + "/billing"

    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)

    workspaceServer.when(
      request()
        .withMethod(POST.name)
        .withPath(billingPath))
      .respond(HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(Created.intValue))

    workspaceServer.when(
      request()
        .withMethod(GET.name)
        .withPath(billingPath + "/project1/members"))
      .respond(HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(OK.intValue))

    List(PUT, DELETE).foreach { method =>
      workspaceServer.when(
        request()
          .withMethod(method.name)
          .withPath(billingPath + "/project2/user/foo@bar.com"))
        .respond(HttpResponse.response()
            .withHeaders(MockUtils.header)
            .withStatusCode(OK.intValue))
    }
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  // streamingPassthrough directive needs to see the routes under "/api", which is how FireCloudApiService starts them
  val testableRoutes = pathPrefix("api") { billingServiceRoutes }

  "BillingApiService" - {
    "create project" in {
      Post("/api/billing") ~> dummyAuthHeaders ~> sealRoute(testableRoutes) ~> check {
        status should be(Created)
      }
    }

    "list project members" in {
      Get("/api/billing/project1/members") ~> dummyAuthHeaders ~> sealRoute(testableRoutes) ~> check {
        status should be(OK)
      }
    }

    "add user" in {
      Put("/api/billing/project2/user/foo@bar.com") ~> dummyAuthHeaders ~> sealRoute(testableRoutes) ~> check {
        status should be(OK)
      }
    }

    "remove user" in {
      Delete("/api/billing/project2/user/foo@bar.com") ~> dummyAuthHeaders ~> sealRoute(testableRoutes) ~> check {
        status should be(OK)
      }
    }
  }
}
