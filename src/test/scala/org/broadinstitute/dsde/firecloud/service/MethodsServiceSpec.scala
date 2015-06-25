package org.broadinstitute.dsde.firecloud.service


import org.broadinstitute.dsde.firecloud.MockServers
import org.broadinstitute.dsde.firecloud.model.MethodEntity
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.vault.common.openam.OpenAMSession
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.http.HttpCookie
import spray.http.HttpHeaders.Cookie
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.testkit.ScalatestRouteTest

class MethodsServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with MethodsService {

  def actorRefFactory = system

  override def beforeAll(): Unit = {
    MockServers.startMethodsServer()
  }

  override def afterAll(): Unit = {
    MockServers.stopMethodsServer()
  }

  "MethodsService" - {

    val openAMSession = OpenAMSession(()).futureValue(timeout(Span(5, Seconds)), interval(scaled(Span(0.5, Seconds))))
    val token = openAMSession.cookies.head.content

    "when generating an OpenAM token" - {
      "token should be valid" in {
        token should endWith("*")
      }
    }

    "when calling GET on the methods path" - {
      "valid methods are returned" in {
        Get("/methods") ~> Cookie(HttpCookie("iPlanetDirectoryPro", token)) ~> sealRoute(listRoute) ~> check {
          status should equal(OK)
          val entities = responseAs[List[MethodEntity]]
          entities shouldNot be(empty)
          entities foreach {
            e: MethodEntity =>
              e.namespace shouldNot be(empty)
          }
        }
      }
    }

    "when calling GET on the methods path without a valid authentication token" - {
      "Found (302 redirect) response is returned" in {
        Get("/methods") ~> sealRoute(listRoute) ~> check {
          status should equal(Found)
        }
      }
    }

    "when calling POST on the methods path" - {
      "MethodNotAllowed error is returned" in {
        Put("/methods") ~> sealRoute(listRoute) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

    "when calling PUT on the methods path" - {
      "MethodNotAllowed error is returned" in {
        Post("/methods") ~> sealRoute(listRoute) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

  }

}
