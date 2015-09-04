package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockMethodsServer
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{Configuration, Method}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.testkit.ScalatestRouteTest

class MethodsServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest
  with Matchers with MethodsService with FireCloudRequestBuilding {

  def actorRefFactory = system

  override def beforeAll(): Unit = {
    MockMethodsServer.startMethodsServer()
  }

  override def afterAll(): Unit = {
    MockMethodsServer.stopMethodsServer()
  }

  "MethodsService" - {

    "when calling GET on the /methods path" - {
      "valid methods are returned" in {
        Get("/methods") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val entities = responseAs[List[Method]]
          entities shouldNot be(empty)
          entities foreach {
            e: Method =>
              e.namespace shouldNot be(empty)
          }
        }
      }
    }

    "when calling GET on the /methods path without a valid authentication token" - {
      "Found (302 redirect) response is returned" in {
        Get("/methods") ~> sealRoute(routes) ~> check {
          status should equal(Found)
        }
      }
    }

    "when calling POST on the /methods path" - {
      "MethodNotAllowed error is returned" in {
        Put("/methods") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

    "when calling PUT on the /methods path" - {
      "MethodNotAllowed error is returned" in {
        Post("/methods") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }


    "when calling GET on the /configurations path" - {
      "valid methods are returned" in {
        Get("/configurations") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
          val entities = responseAs[List[Configuration]]
          entities shouldNot be(empty)
          entities foreach {
            e: Configuration =>
              e.namespace shouldNot be(empty)
          }
        }
      }
    }

    "when calling GET on the /configurations path without a valid authentication token" - {
      "Found (302 redirect) response is returned" in {
        Get("/configurations") ~> sealRoute(routes) ~> check {
          status should equal(Found)
        }
      }
    }

    "when calling POST on the /configurations path" - {
      "MethodNotAllowed error is returned" in {
        Put("/configurations") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

    "when calling PUT on the /configurations path" - {
      "MethodNotAllowed error is returned" in {
        Post("/configurations") ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          responseAs[String] === "HTTP method not allowed, supported methods: GET"
        }
      }
    }

  }

}
