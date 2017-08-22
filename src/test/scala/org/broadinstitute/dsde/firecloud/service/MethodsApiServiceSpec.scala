package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiService
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.HttpMethods
import spray.http.StatusCodes._

class MethodsApiServiceSpec extends BaseServiceSpec with MethodsApiService {

  val methodsPermPath="/methods/namespace/name/1/permissions"
  val configsPermPath="/configurations/namespace/name/1/permissions"

  def actorRefFactory = system
  val methodsServiceConstructor: (UserInfo) => MethodsService = MethodsService.constructor(app)

  var methodsServer: ClientAndServer = _
  val httpMethods = List(HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT,
    HttpMethods.DELETE, HttpMethods.PATCH, HttpMethods.OPTIONS, HttpMethods.HEAD)

  override def beforeAll(): Unit = {
    methodsServer = startClientAndServer(MockUtils.methodsServerPort)
    httpMethods map {
      method =>
        methodsServer
          .when(request().withMethod(method.name).withPath(MethodsApiService.remoteMethodsPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
        methodsServer
          .when(request().withMethod(method.name).withPath(MethodsApiService.remoteConfigurationsPath))
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
    "when testing all HTTP methods on the methods path" - {
      "MethodNotAllowed error is not returned" in {
        httpMethods map {
          method =>
            new RequestBuilder(method)("/" + localMethodsPath) ~> sealRoute(methodsServiceApiRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
            new RequestBuilder(method)("/" + localConfigsPath) ~> sealRoute(methodsServiceApiRoutes) ~> check {
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }
    }
  }

}
