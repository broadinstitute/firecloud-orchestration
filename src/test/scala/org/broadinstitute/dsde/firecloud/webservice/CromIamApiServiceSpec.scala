package org.broadinstitute.dsde.firecloud.webservice

import akka.actor.ActorRefFactory
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, ServiceSpec}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import spray.http.HttpMethods
import spray.httpx.SprayJsonSupport

class CromIamApiServiceSpec extends BaseServiceSpec with CromIamApiService with SprayJsonSupport {

  // The route tests pass without the mock server, but we attempt to contact `http://localhost:8995`
  // and print out a hideous stacktrace if nothing is running there. If there is a smarter way to do
  // this, please speak up!
  var cromiamServer: ClientAndServer = _

  override def beforeAll(): Unit = {
    cromiamServer = startClientAndServer(MockUtils.cromiamServerPort)
  }

  override def afterAll(): Unit = {
    cromiamServer.stop()
  }

  "CromIAM passthrough for Job Manager" - {

    lazy val workflowRoot: String = "/workflows/v1"
    lazy val engineRoot: String = "/engine/v1"

    "/workflows/{version}/abort" - {

      val endpoint = workflowRoot + "/my-bogus-workflow-id-565656/abort"
      val myMethods = List(HttpMethods.POST)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

      "should reject everything else" in {
        allHttpMethodsExcept(myMethods) foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = false)
        }
      }
    }

    "/workflows/{version}/labels" - {

      val endpoint = workflowRoot + "/my-bogus-workflow-id-565656/labels"
      val myMethods = List(HttpMethods.PATCH)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

      "should reject everything else" in {
        allHttpMethodsExcept(myMethods) foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = false)
        }
      }
    }

    "/workflows/{version}/metadata" - {

      val endpoint = workflowRoot + "/my-bogus-workflow-id-565656/metadata"
      val myMethods = List(HttpMethods.GET)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

      "should reject everything else" in {
        allHttpMethodsExcept(myMethods) foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = false)
        }
      }
    }

    "/workflows/{version}/query" - {

      val endpoint = workflowRoot + "/query"
      val myMethods = List(HttpMethods.GET, HttpMethods.POST)

      "should pass through my methods" in {
        myMethods foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = true)
        }
      }

      "should reject everything else" in {
        allHttpMethodsExcept(myMethods) foreach { method =>
          checkIfPassedThrough(cromIamApiServiceRoutes, method, endpoint, toBeHandled = false)
        }
      }
    }

  }

  override implicit def actorRefFactory: ActorRefFactory = system
}
