package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{HttpMethods, Uri}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import scala.concurrent.ExecutionContext

class CromIamApiServiceSpec extends BaseServiceSpec with CromIamApiService with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  var cromiamServer: ClientAndServer = _
  var workspaceServer: ClientAndServer = _

  override def beforeAll(): Unit = {
    cromiamServer = startClientAndServer(MockUtils.cromiamServerPort)
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)
  }

  override def afterAll(): Unit = {
    cromiamServer.stop()
    workspaceServer.stop()
  }

  "CromIAM passthrough" - {

    lazy val workflowRoot: String = "/workflows/v1"
    lazy val engineRoot: String = "/engine/v1"
    lazy val womtoolRoot: String = "/womtool/v1"

    "/api/womtool/{version}/describe" - {

      val endpoint = s"$womtoolRoot/describe"

      "should pass through my methods" in {
        checkIfPassedThrough(cromIamApiServiceRoutes, HttpMethods.POST, endpoint, toBeHandled = true)
      }

    }

    "/api/workflows/{version}/abort" - {

      val endpoint = workflowRoot + "/my-bogus-workflow-id-565656/abort"
      val myMethods = List(HttpMethods.POST)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

    }

    "/api/workflows/{version}/releaseHold" - {

      val endpoint = workflowRoot + "/my-bogus-workflow-id-565656/releaseHold"
      val myMethods = List(HttpMethods.POST)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

    }

    "/api/workflows/{version}/labels" - {

      val endpoint = workflowRoot + "/my-bogus-workflow-id-565656/labels"
      val myMethods = List(HttpMethods.PATCH)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

    }

    "/api/workflows/{version}/metadata" - {

      val endpoint = workflowRoot + "/my-bogus-workflow-id-565656/metadata"
      val myMethods = List(HttpMethods.GET)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

      "should forward query parameters on GET" in {

        val request = org.mockserver.model.HttpRequest.request()
          .withMethod("GET")
          .withPath(s"/api$endpoint")
          .withQueryStringParameter("includeKey", "hit")
          .withQueryStringParameter("includeKey", "hitFailure")

        val response = org.mockserver.model.HttpResponse.response()
          .withStatusCode(200)
          .withBody("We got all of your includeKeys")

        cromiamServer
          .when(request)
          .respond(response)

        Get(Uri(endpoint).withQuery(Query("includeKey=hit&includeKey=hitFailure"))) ~> dummyUserIdHeaders("1234") ~> sealRoute(cromIamApiServiceRoutes) ~> check {
          cromiamServer.verify(request)

          status.intValue should equal(200)
          responseAs[String] shouldEqual "We got all of your includeKeys"
        }
      }

    }

    "/api/workflows/{version}/backend/metadata" - {

      // N.B. these passthroughs hit Rawls, not Cromiam (see CromIamApiService). Therefore they use the
      // "workspaceServer" mockserver, not the "cromiamServer" mockserver.

      val endpointPapiV1 = workflowRoot + "/my-bogus-workflow-id-565656/backend/metadata/operations/foobar"
      val endpointPapiV2 = workflowRoot + "/my-bogus-workflow-id-565656/backend/metadata/projects/proj/operations/foobar"
      val myMethods = List(HttpMethods.GET)

      "should pass through my methods PAPIv1" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpointPapiV1, toBeHandled = true)
        }
      }

      "should pass through my methods PAPIv2" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpointPapiV2, toBeHandled = true)
        }
      }

    }

    "/api/workflows/{version}/query" - {

      val endpoint = workflowRoot + "/query"
      val myMethods = List(HttpMethods.GET, HttpMethods.POST)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

      "should forward query parameters on GET" in {

        val request = org.mockserver.model.HttpRequest.request()
          .withMethod("GET")
          .withPath(s"/api$endpoint")
          .withQueryStringParameter("start", "start value")
          .withQueryStringParameter("end", "end value")

        val response = org.mockserver.model.HttpResponse.response()
          .withStatusCode(200)
          .withBody("Got a query with start and end values")

        cromiamServer
          .when(request)
          .respond(response)

        Get(Uri(endpoint).withQuery(Query("start=start%20value&end=end%20value"))) ~> dummyUserIdHeaders("1234") ~> sealRoute(cromIamApiServiceRoutes) ~> check {
          cromiamServer.verify(request)

          status.intValue should equal(200)
          responseAs[String] shouldEqual "Got a query with start and end values"
        }
      }

    }

    "/api/workflows/{version}/callcaching/diff" - {

      val endpoint = workflowRoot + "/callcaching/diff"
      val myMethods = List(HttpMethods.GET)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

      "should forward query parameters on GET" in {

        val request = org.mockserver.model.HttpRequest.request()
          .withMethod("GET")
          .withPath(s"/api$endpoint")
          .withQueryStringParameter("workflowA", "workflowA value")
          .withQueryStringParameter("workflowB", "workflowB value")

        val response = org.mockserver.model.HttpResponse.response()
          .withStatusCode(200)
          .withBody("Got a query with workflowA and workflowB values")

        cromiamServer
          .when(request)
          .respond(response)

        Get(Uri(endpoint).withQuery(Query("workflowA=workflowA%20value&workflowB=workflowB%20value"))) ~> dummyUserIdHeaders("1234") ~> sealRoute(cromIamApiServiceRoutes) ~> check {
          cromiamServer.verify(request)

          status.intValue should equal(200)
          responseAs[String] shouldEqual "Got a query with workflowA and workflowB values"
        }
      }

    }

    "/engine/{version}/status" - {
      val endpoint = engineRoot + "/status"
      val myMethods = List(HttpMethods.GET)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamEngineRoutes, method, endpoint, toBeHandled = true)
        }
      }

    }

    "/engine/{version}/version" - {
      val endpoint = engineRoot + "/version"
      val myMethods = List(HttpMethods.GET)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamEngineRoutes, method, endpoint, toBeHandled = true)
        }
      }

    }

  }
}
