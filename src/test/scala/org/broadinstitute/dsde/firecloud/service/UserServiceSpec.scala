package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._

import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

class UserServiceSpec extends ServiceSpec with UserService {

  def actorRefFactory = system
  var profileServer: ClientAndServer = _
  val httpMethods = List(HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT,
    HttpMethods.DELETE, HttpMethods.PATCH, HttpMethods.OPTIONS, HttpMethods.HEAD)

  val uniqueId = "1234"
  val exampleKey = "favoriteColor"
  val exampleVal = "green"
  val fullProfile = Profile(
    name = MockUtils.randomAlpha(),
    email = MockUtils.randomAlpha(),
    institution = MockUtils.randomAlpha(),
    pi = MockUtils.randomAlpha()
  )
  val allProperties: Map[String, String] = fullProfile.propertyValueMap

  override def beforeAll(): Unit = {
    profileServer = startClientAndServer(MockUtils.thurloeServerPort)
    // Generate a mock response for all combinations of profile properties
    // to ensure that all posts to any combination will yield a successful response.
    allProperties.keys map {
      key =>
        profileServer
          .when(request().withMethod("POST").withPath(
            UserService.remoteGetKeyPath.format(uniqueId, key)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }

    List(HttpMethods.GET, HttpMethods.POST, HttpMethods.DELETE) map {
      method =>
        profileServer
          .when(request().withMethod(method.name).withPath(
            UserService.remoteGetKeyPath.format(uniqueId, exampleKey)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
    profileServer
      .when(request().withMethod("GET").withPath(UserService.remoteGetAllPath.format(uniqueId)))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    profileServer
      .when(request().withMethod("POST").withPath(UserService.remoteSetKeyPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
  }

  override def afterAll(): Unit = {
    profileServer.stop()
  }

  "UserService" - {

    "when GET-ting or DELETE-ing a single profile key" - {
      "MethodNotAllowed response is not returned" in {
        List(HttpMethods.GET, HttpMethods.DELETE) map {
          method =>
            new RequestBuilder(method)(s"/profile/$exampleKey") ~>
              dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
              log.debug(s"$method /profile/$exampleKey: " + status)
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }
    }

    "when POST-ting a single profile key" - {
      "MethodNotAllowed response is not returned" in {
        allProperties foreach {
          (e: (String, String)) =>
            val payload = ThurloeKeyValue(Some(uniqueId),
              Some(FireCloudKeyValue(Some(e._1), Some(e._2))))
            Post(s"/profile/$exampleKey") ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
              log.debug(s"POST /profile/$exampleKey: " + status)
              status shouldNot equal(MethodNotAllowed)
            }
        }
      }
    }

    "when calling other methods a single profile key" - {
      "MethodNotAllowed response is returned" in {
        List(HttpMethods.PUT, HttpMethods.PATCH) map {
          method =>
            new RequestBuilder(method)(s"/profile/$exampleKey") ~>
              dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
              log.debug(s"$method /profile/$exampleKey: " + status)
              status should be (MethodNotAllowed)
            }
        }
      }
    }

    "when GET-ting all profile information" - {
      "MethodNotAllowed response is not returned" in {
        Get("/profile") ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          log.debug("GET /profile: " + status)
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }


    // Testing the only non-passthrough routes in UserService so we are testing for correct status
    "when POST-ting a complete profile" - {
      "OK response is returned" in {
        Post("/profile", fullProfile) ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          log.debug("POST /profile: " + status)
          status should equal(OK)
        }
      }
    }

    // Testing the only non-passthrough routes in UserService so we are testing for correct status
    "when POST-ting an incomplete profile" - {
      "BadRequest response is not returned" in {
        val incompleteProfile = Map("name" -> MockUtils.randomAlpha())
        Post("/profile", incompleteProfile) ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(routes) ~> check {
          log.debug("POST /profile: " + status)
          status should equal(BadRequest)
        }
      }
    }

  }

}
