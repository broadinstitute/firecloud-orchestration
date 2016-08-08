package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.HttpMethods
import spray.http.StatusCodes._

class BillingServiceSpec extends ServiceSpec with BillingService {

  def actorRefFactory = system
  var workspaceServer: ClientAndServer = _

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)

    workspaceServer.when(request().withMethod(HttpMethods.POST.name).withPath(BillingService.billingPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(Created.intValue)
      )
    workspaceServer.when(request().withMethod(HttpMethods.GET.name).withPath(BillingService.billingPath + "/project1/members"))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    List(HttpMethods.PUT, HttpMethods.DELETE).foreach { method =>
      workspaceServer.when(request().withMethod(method.name).withPath(BillingService.billingPath + "/project1/user/foo@bar.com"))
        .respond(
          org.mockserver.model.HttpResponse.response()
            .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
        )
    }
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "BillingService" - {
    "create project" in {
      Post("/billing") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
        status should be(Created)
      }
    }
    "list project members" in {
      Get("/billing/project1/members") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
        status should be(OK)
      }
    }
    "add user" in {
      Put("/billing/project1/user/foo@bar.com") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
        status should be(OK)
      }
    }
    "remove user" in {
      Delete("/billing/project1/user/foo@bar.com") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
        status should be(OK)
      }
    }
  }

}
