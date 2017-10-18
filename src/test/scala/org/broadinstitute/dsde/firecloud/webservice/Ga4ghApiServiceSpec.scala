package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import org.scalatest.BeforeAndAfterEach
import spray.http.HttpMethods
import spray.http.StatusCodes._

class Ga4ghApiServiceSpec extends BaseServiceSpec with Ga4ghApiService with BeforeAndAfterEach {

  def actorRefFactory: ActorSystem = system

  var toolRegistryServer: ClientAndServer = _
  val toolPaths = List(
    "/ga4gh/v1/metadata",
    "/ga4gh/v1/tool-classes",
    "/ga4gh/v1/tools",
    "/ga4gh/v1/tools/namespace:name",
    "/ga4gh/v1/tools/namespace:name/versions",
    "/ga4gh/v1/tools/namespace:name/versions/1",
    "/ga4gh/v1/tools/namespace:name/versions/1/WDL/descriptor")

  val unImplementedPaths = List(
    "/ga4gh/v1/tools/namespace:name/versions/1/dockerfile",
    "/ga4gh/v1/tools/namespace:name/versions/1/WDL/descriptor/1",
    "/ga4gh/v1/tools/namespace:name/versions/1/WDL/tests")

  override def beforeAll(): Unit = {
    toolRegistryServer = startClientAndServer(MockUtils.methodsServerPort)
    toolPaths.map { path =>
      toolRegistryServer.when(request().withMethod(HttpMethods.GET.name).withPath(path))
        .respond(
          org.mockserver.model.HttpResponse.response()
            .withStatusCode(OK.intValue)
        )
    }
  }

  override def afterAll(): Unit = {
    toolRegistryServer.stop()
  }

  "GA4GH API service" - {
    "Tool Registry" - {
      "passthrough APIs" - {
        "should handle un-implemented paths" in {
          unImplementedPaths.map { path =>
            Get(path) ~> ga4ghRoutes ~> check {
              status should equal(NotImplemented)
            }
          }
        }
        "should reject all but GET" in {
          List(HttpMethods.POST, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.DELETE, HttpMethods.HEAD) foreach {
            method =>
              (toolPaths ++ unImplementedPaths).map { path =>
                new RequestBuilder(method)(path) ~> ga4ghRoutes ~> check {
                  assert(!handled)
                }
              }
          }
        }
        "should accept GET" in {
          toolPaths.map { path =>
            Get(path) ~> ga4ghRoutes ~> check {
              assert(handled)
            }
          }
        }
      }
    }
  }

}
