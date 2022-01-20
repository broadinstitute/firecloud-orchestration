package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethod
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.{AgoraPermissionService, BaseServiceSpec, ServiceSpec}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.mock.action.ExpectationCallback
import org.mockserver.model.HttpClassCallback.callback
import org.mockserver.model.HttpRequest._
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._
import org.broadinstitute.dsde.firecloud.model.UserInfo

import scala.concurrent.ExecutionContext

final class MethodsApiServiceSpec extends BaseServiceSpec with ServiceSpec with MethodsApiService {

  def actorRefFactory:ActorSystem = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  var methodsServer: ClientAndServer = _

  val agoraPermissionService: (UserInfo) => AgoraPermissionService = AgoraPermissionService.constructor(app)

  case class Api(localPath: String, verb: HttpMethod, remotePath: String, allowQueryParams: Boolean)

  /*
    list all the passthrough routes in MethodsApiService.

    orchestration routes as seen by the end user are /api/methods/etc.... But, the MethodsApiService
    defines them as /methods/etc... and they are later wrapped by FireCloudServiceActor in "/api".
    So, our unit tests here use the values defined in MethodsApiService, without the "/api".

    NB: we don't test the permissions endpoints here, because they are not passthroughs;
    those are tested elsewhere
  */
  val testCases = Seq(
    Api("/configurations", GET, "/api/v1/configurations", allowQueryParams=true),
    Api("/configurations", POST, "/api/v1/configurations", allowQueryParams=false),
    Api("/configurations/namespace/name/1", GET, "/api/v1/configurations/namespace/name/1", allowQueryParams=true),
    Api("/configurations/namespace/name/1", DELETE, "/api/v1/configurations/namespace/name/1", allowQueryParams=false),
    Api("/methods", GET, "/api/v1/methods", allowQueryParams=true),
    Api("/methods", POST, "/api/v1/methods", allowQueryParams=false),
    Api("/methods/namespace/name/1", GET, "/api/v1/methods/namespace/name/1", allowQueryParams=true),
    Api("/methods/namespace/name/1", DELETE, "/api/v1/methods/namespace/name/1", allowQueryParams=false),
    Api("/methods/namespace/name/1", POST, "/api/v1/methods/namespace/name/1", allowQueryParams=true),
    Api("/methods/namespace/name/1/configurations", GET, "/api/v1/methods/namespace/name/1/configurations", allowQueryParams=false),
    Api("/methods/definitions", GET, "/api/v1/methods/definitions", allowQueryParams=false),
    Api("/methods/namespace/name/configurations", GET, "/api/v1/methods/namespace/name/configurations", allowQueryParams=false)
  )

  /*
    given the above test cases, find all the remaining HTTP methods that we should NOT support - we'll
    use these for negative test cases.

    NB: this negative-test determination will fail if/when we define some paths to have both passthrough and
    non-passthrough routes for different http verbs. As of this writing, each path is either all passthrough
    or all non-passthrough.
   */
  val negativeCases: Map[String, Seq[HttpMethod]] = testCases
    .groupBy(_.localPath)
    .map(api => api._1 -> api._2.map(_.verb))
    .map(neg => neg._1 -> allHttpMethodsExcept(neg._2))

  // pick a status code to represent success in our mocks that we don't use elsewhere.
  // this guarantees the code is coming from our mock, not from some other route.
  val mockSuccessResponseCode = NonAuthoritativeInformation

  override def beforeAll(): Unit = {
    methodsServer = startClientAndServer(MockUtils.methodsServerPort)

    // for each test case, set up an agora mock that echoes back to us the method, path, and presence/absence of
    // query params that were actually used in the passthrough request.
    testCases foreach { api =>
      methodsServer
        .when(request().withMethod(api.verb.name).withPath(api.remotePath))
          .callback(callback().withCallbackClass("org.broadinstitute.dsde.firecloud.webservice.MethodsApiServiceSpecCallback"))
    }
  }

  override def afterAll(): Unit = {
    methodsServer.stop()
  }

  // tests
  "MethodsApiService uses of passthrough directive" - {
    testCases foreach { api =>
      s"should succeed on ${api.verb.toString} to ${api.localPath}" in {
        // always send query params to the orch endpoint, to simulate an end user manually adding them.
        // we'll check inside the test whether or not the query params are sent through to agora.
        new RequestBuilder(api.verb)(api.localPath + "?foo=bar&baz=qux") ~> methodsApiServiceRoutes ~> check {
          assertResult(mockSuccessResponseCode) {
            status
          }
          val passthroughResult = responseAs[String].split(" ")

          val queryParamMsg = if (api.allowQueryParams) "allow" else "omit"

          assertResult(api.verb.value, "unexpected http verb in passthrough") {
            passthroughResult(0)
          }
          assertResult(api.remotePath, "unexpected uri path in passthrough") {
            passthroughResult(1)
          }
          assertResult(api.allowQueryParams, s"passthrough should $queryParamMsg query params") {
            passthroughResult(2).toBoolean
          }
        }
      }
    }
    // negative tests
    negativeCases foreach { neg =>
      neg._2 foreach { verb =>
        s"should reject a ${verb.toString} to ${neg._1}" in {
          new RequestBuilder(verb)(neg._1) ~> methodsApiServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }
    }
  }
}

final class MethodsApiServiceSpecCallback extends ExpectationCallback {
  override def handle(httpRequest: HttpRequest): HttpResponse = {
    val method:String = httpRequest.getMethod.getValue
    val path:String = httpRequest.getPath.getValue
    val hasParams:Boolean = !httpRequest.getQueryStringParameters.isEmpty

    val content = s"$method $path $hasParams"

    val resp = response().withHeaders(MockUtils.header).withStatusCode(NonAuthoritativeInformation.intValue).withBody(content)
    resp
  }
}
