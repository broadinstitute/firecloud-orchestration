package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

final class BillingServiceSpec extends BaseServiceSpec with BillingService {
  def actorRefFactory = system
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

  "BillingService" - {
    "create project" in {
      Post("/billing") ~> dummyAuthHeaders ~> sealRoute(billingServiceRoutes) ~> check {
        status should be(Created)
      }
    }

    "list project members" in {
      Get("/billing/project1/members") ~> dummyAuthHeaders ~> sealRoute(billingServiceRoutes) ~> check {
        status should be(OK)
      }
    }

    "add user" in {
      Put("/billing/project2/user/foo@bar.com") ~> dummyAuthHeaders ~> sealRoute(billingServiceRoutes) ~> check {
        status should be(OK)
      }
    }

    "remove user" in {
      Delete("/billing/project2/user/foo@bar.com") ~> dummyAuthHeaders ~> sealRoute(billingServiceRoutes) ~> check {
        status should be(OK)
      }
    }
  }
}
