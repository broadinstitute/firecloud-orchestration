package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.{Accept, Authorization, Host, OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import spray.json.{JsObject, JsString}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.util.Try

class StreamingPassthroughSpec extends AnyFreeSpec
  with Matchers with BeforeAndAfterAll with ScalatestRouteTest
  with StreamingPassthrough {

  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

  val localMockserverPort = 9797
  var localMockserver: ClientAndServer = _

  // list of status codes that are testable - note this does not include 100-level information responses,
  // which seem to cause problems in the connection to mockserver
  val testableStatusCodes: Seq[StatusCode] = (200 to 599) flatMap { intcode =>
    Try(StatusCode.int2StatusCode(intcode)).toOption
  }

  override protected def beforeAll(): Unit = {
    localMockserver = startClientAndServer(localMockserverPort)

    // set up mockserver responses for each testable status code
    testableStatusCodes foreach { statusCode =>
      val request = org.mockserver.model.HttpRequest.request()
        .withMethod("GET")
        .withPath(s"/statuscode/checker/${statusCode.intValue()}")

      val response = org.mockserver.model.HttpResponse.response()
        .withStatusCode(statusCode.intValue())
        .withBody(statusCode.reason)

      localMockserver
        .when(request)
        .respond(response)
    }
  }

  override protected def afterAll(): Unit = {
    localMockserver.stop()
  }


  "convertToTargetUri" - {
    "should calculate a remainder" in {
      val requestUri = Uri("http://localhost:8123/foo/bar/baz/qux")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val expected = Uri("https://example.com/api/version/foo/baz/qux")
      convertToRemoteUri(requestUri, localBasePath, remoteBaseUri) shouldBe expected
    }
    "should pass on a querystring" in {
      val requestUri = Uri("http://localhost:8123/foo/bar/baz/qux?hello=world")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val expected = Uri("https://example.com/api/version/foo/baz/qux?hello=world")
      convertToRemoteUri(requestUri, localBasePath, remoteBaseUri) shouldBe expected
    }
    "should handle the base path being present more than once" in {
      val requestUri = Uri("http://localhost:8123/foo/bar/baz/qux/foo/bar/baz")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val expected = Uri("https://example.com/api/version/foo/baz/qux/foo/bar/baz")
      convertToRemoteUri(requestUri, localBasePath, remoteBaseUri) shouldBe expected
    }
    "should be case-sensitive in remainder calculation" in {
      val requestUri = Uri("http://localhost:8123/Foo/Bar/baz/qux")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val exception = intercept[Exception] {
        convertToRemoteUri(requestUri, localBasePath, remoteBaseUri)
      }

      exception.getMessage shouldBe "request path doesn't start properly: /Foo/Bar/baz/qux does not start with /foo/bar"
    }
    "should handle an empty remainder" in {
      val requestUri = Uri("http://localhost:8123/foo/bar")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val expected = Uri("https://example.com/api/version/foo")
      convertToRemoteUri(requestUri, localBasePath, remoteBaseUri) shouldBe expected
    }
    "should handle trailing slash as remainder" in {
      val requestUri = Uri("http://localhost:8123/foo/bar/")
      val localBasePath = Path("/foo/bar")
      val remoteBaseUri = Uri("https://example.com/api/version/foo")

      val expected = Uri("https://example.com/api/version/foo/")
      convertToRemoteUri(requestUri, localBasePath, remoteBaseUri) shouldBe expected
    }
  }

  "transformToPassthroughRequest" - {

    // fixtures for the next set of tests
    val fixtureHeaders = Seq(Accept(Seq(MediaRanges.`application/*`)))
    val fixtureRequest = HttpRequest(method = HttpMethods.POST,
      uri = Uri("http://localhost:8123/foo/bar/baz/qux"),
      headers = fixtureHeaders)

    "should NOT forward Timeout-Access header" in {
      val requestHeaders = fixtureHeaders :+ RawHeader("Timeout-Access", "doesnt matter")
      val expectedHeaders = fixtureHeaders :+ Host("example.com")
      val req = fixtureRequest.withHeaders(requestHeaders)
      // call transformToPassthroughRequest
      val actual = transformToPassthroughRequest(Path("/foo/bar"), Uri("https://example.com/api/version/foo"))(req)
      actual.headers should contain theSameElementsAs (expectedHeaders)
    }
    "should NOT forward Host header" in {
      val requestHeaders = fixtureHeaders :+ Host("overwritten")
      val expectedHeaders = fixtureHeaders :+ Host("example.com")
      val req = fixtureRequest.withHeaders(requestHeaders)
      // call transformToPassthroughRequest
      val actual = transformToPassthroughRequest(Path("/foo/bar"), Uri("https://example.com/api/version/foo"))(req)
      actual.headers should contain theSameElementsAs (expectedHeaders)
    }
    "should forward Authorization header" in {
      val requestHeaders = fixtureHeaders :+ Authorization(OAuth2BearerToken("123456"))
      val expectedHeaders = requestHeaders :+ Host("example.com")
      val req = fixtureRequest.withHeaders(requestHeaders)
      // call transformToPassthroughRequest
      val actual = transformToPassthroughRequest(Path("/foo/bar"), Uri("https://example.com/api/version/foo"))(req)
      actual.headers should contain theSameElementsAs(expectedHeaders)
    }
    "should forward miscellaneous headers" in {
      val requestHeaders = fixtureHeaders :+ RawHeader("X-FireCloud-Id", FireCloudConfig.FireCloud.fireCloudId)
      val expectedHeaders = requestHeaders :+ Host("example.com")
      val req = fixtureRequest.withHeaders(requestHeaders)
      // call transformToPassthroughRequest
      val actual = transformToPassthroughRequest(Path("/foo/bar"), Uri("https://example.com/api/version/foo"))(req)
      actual.headers should contain theSameElementsAs (expectedHeaders)
    }
    List(CONNECT, DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT, TRACE) foreach { methodUnderTest =>
      s"should preserve request method $methodUnderTest" in {
        val req = fixtureRequest.withMethod(methodUnderTest)
        val actual = transformToPassthroughRequest(Path("/foo/bar"), Uri("https://example.com/api/version/foo"))(req)
        actual.method shouldBe methodUnderTest
      }
    }
    "should preserve request entity" in {
      val randomJson = JsObject("mykey" -> JsString(UUID.randomUUID().toString))
      val requestEntity = HttpEntity.Strict(ContentTypes.`application/json`, ByteString.apply(randomJson.compactPrint))
      val req = fixtureRequest.withEntity(requestEntity)
      val actual = transformToPassthroughRequest(Path("/foo/bar"), Uri("https://example.com/api/version/foo"))(req)
      actual.entity shouldBe requestEntity
    }
  }

  "mockserver-based tests" - {

    val testRoute = {
      streamingPassthrough(Uri(s"http://localhost:$localMockserverPort/statuscode/checker"))
    }

    testableStatusCodes foreach { codeUnderTest =>
      s"should reply with remote-system ${codeUnderTest.intValue} (${codeUnderTest.reason()}) responses" in {
        Get(s"/${codeUnderTest.intValue}") ~> testRoute ~> check {
          status shouldBe codeUnderTest
          if (codeUnderTest.allowsEntity()) {
            responseAs[String] shouldBe codeUnderTest.reason
          }
        }
      }
    }
  }



}
