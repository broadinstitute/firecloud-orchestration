package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpMethods
import org.broadinstitute.dsde.firecloud.service.PassthroughDirectivesSpec._
import org.broadinstitute.dsde.firecloud.service.PassthroughDirectivesSpecSupport._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpClassCallback.callback
import org.mockserver.model.HttpRequest.request
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._

import scala.concurrent.ExecutionContext
import akka.http.scaladsl.server.Route.{seal => sealRoute}

object PassthroughDirectivesSpec {
  val echoPort = 9123
  val echoUrl = s"http://localhost:$echoPort"
}

final class PassthroughDirectivesSpec extends BaseServiceSpec with FireCloudDirectives with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  var echoServer: ClientAndServer = _

  override def beforeAll() = {
    echoServer = startClientAndServer(echoPort)
      echoServer.when(request())
        .callback(
          callback().
            withCallbackClass("org.broadinstitute.dsde.firecloud.service.EchoCallback"))
  }

  override def afterAll() = {
    echoServer.stop
  }

  "Passthrough Directives" - {
    "passthrough() directive" - {
      "root path '/'" - {
        "should hit the root path '/'" in {
          validateUri("/")
        }
      }

      "path with multiple segments" - {
        "should hit the path exactly" in {
          validateUri("/one/2/three")
        }
      }

      "path with a hex-encoded segment" - {
        "should hit the path exactly" in {
          validateUri(
            "/one/%32/three",
            "/one/2/three"
          )
        }
      }

      "path with a single query parameter" - {
        "should send the query parameter through" in {
          validateUri("/one/2/three?key=value", Some(Map("key"->"value")))
        }
      }

      "path with multiple query parameters" - {
        "should send the query parameters through" in {
          validateUri("/one/2/three?key=value&key2=val2", Some(Map("key"->"value", "key2"->"val2")))
        }
      }

      "path with encoded query parameters" - {
        "should send the query parameters through" in {
          validateUri(
            "/one/2/three?key=value&key2=1%323",
            "/one/2/three?key=value&key2=123",
            Some(Map("key"->"value", "key2"->"123"))
          )
        }
      }

      "all http methods" - {
        allHttpMethods foreach { meth =>
          s"$meth should pass through correctly" in {
            val specRoute = passthrough(echoUrl + "/", meth)
            val reqMethod = new RequestBuilder(meth)
            reqMethod() ~> sealRoute(specRoute) ~> check {
              assertResult(OK) {status}
              // special handling for HEAD, because HEAD won't return a body
              if (meth != HEAD && meth != CONNECT) {
                val info = responseAs[RequestInfo]
                assertResult(echoUrl + "/") {
                  info.url
                }
                assertResult(meth.value, s"testing http method ${meth.value}") {
                  info.method
                }
              }
            }
          }
        }
      }
    }
  }

  private def validateUri(path: String, queryParams: Option[Map[String, String]] = None): Unit = {
    validateUri(path, path, queryParams)
  }

  private def validateUri(inpath: String, outpath: String): Unit = {
    validateUri(inpath, outpath, None)
  }

  private def validateUri(inpath: String, outpath: String, queryParams: Option[Map[String, String]]): Unit = {
    val specRoute = passthrough(echoUrl + inpath, HttpMethods.GET)
    Get() ~> sealRoute(specRoute) ~> check {
      val info = responseAs[RequestInfo]
      assertResult(echoUrl + outpath) {
        info.url
      }
      if (queryParams.isDefined) {
        assertResult(queryParams.get) {
          info.queryparams
        }
      }
    }
  }
}
