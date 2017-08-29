package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.HttpMethods
import spray.http.StatusCodes._

class MethodsApiServiceSpec extends ServiceSpec with MethodsApiService {

  val methodsPermPath="/methods/namespace/name/1/permissions"
  val configsPermPath="/configurations/namespace/name/1/permissions"

  def actorRefFactory = system
  var methodsServer: ClientAndServer = _
  val httpMethods = List(HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT,
    HttpMethods.DELETE, HttpMethods.PATCH, HttpMethods.OPTIONS, HttpMethods.HEAD)

  override def beforeAll(): Unit = {
    methodsServer = startClientAndServer(MockUtils.methodsServerPort)
    httpMethods foreach {
      method =>
        methodsServer
          .when(request().withMethod(method.name).withPath(remoteMethodsPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
        methodsServer
          .when(request().withMethod(method.name).withPath(remoteConfigurationsPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
  }

  override def afterAll(): Unit = {
    methodsServer.stop()
  }

  "MethodsService" - {
    "when testing all HTTP methods on the methods and configuratins paths" - {

      val allowedMethods = List(HttpMethods.GET, HttpMethods.POST)
      val disallowedMethods = httpMethods diff allowedMethods

      "MethodNotAllowed error is not returned for allowed methods" in {
        allowedMethods foreach {
          method =>
            new RequestBuilder(method)("/" + localMethodsPath) ~> sealRoute(methodsApiServiceRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
            new RequestBuilder(method)("/" + localConfigsPath) ~> sealRoute(methodsApiServiceRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }

      "MethodNotAllowed error is returned for disallowed methods" in {
        disallowedMethods foreach {
          method =>
            new RequestBuilder(method)("/" + localMethodsPath) ~> sealRoute(methodsApiServiceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
            new RequestBuilder(method)("/" + localConfigsPath) ~> sealRoute(methodsApiServiceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }


    }
  }

}
