package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.scalatest.BeforeAndAfterEach
import spray.http.HttpMethods
import spray.http.StatusCodes._

class Ga4ghApiServiceSpec extends BaseServiceSpec with Ga4ghApiService with BeforeAndAfterEach {

  def actorRefFactory = system

  var toolRegistryServer: ClientAndServer = _
  val toolRegistryDummyUrlPath = "/ga4gh/tools/namespace:name/versions/1/WDL/descriptor"

  override def beforeAll(): Unit = {
    toolRegistryServer = startClientAndServer(MockUtils.methodsServerPort)

    toolRegistryServer.when(request().withMethod(HttpMethods.GET.name).withPath(toolRegistryDummyUrlPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withStatusCode(OK.intValue)
      )
  }

  override def afterAll(): Unit = {
    toolRegistryServer.stop()
  }

  "GA4GH API service" - {
    "Tool Registry" - {
      "get-descriptor passthrough API" - {

        "should reject all but GET" in {
          List(HttpMethods.POST, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE, HttpMethods.HEAD) foreach {
            method =>
              new RequestBuilder(method)(toolRegistryDummyUrlPath) ~> ga4ghRoutes ~> check {
                assert(!handled)
              }
          }
        }
        "should accept GET" in {
          Get(toolRegistryDummyUrlPath) ~> ga4ghRoutes ~> check {
           assert(handled)
          }
        }
      }
    }
  }

}
