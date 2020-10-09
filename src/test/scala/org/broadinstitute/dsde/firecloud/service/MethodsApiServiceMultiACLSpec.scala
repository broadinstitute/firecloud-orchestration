package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockAgoraACLServer
import org.broadinstitute.dsde.firecloud.model.MethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impFireCloudPermission, impMethodAclPair}
import org.broadinstitute.dsde.firecloud.webservice.MethodsApiService
import org.broadinstitute.dsde.rawls.model.MethodRepoMethod
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.server.Route.{seal => sealRoute}


class MethodsApiServiceMultiACLSpec extends ServiceSpec with MethodsApiService {

  def actorRefFactory = system


  override def beforeAll(): Unit = {
    MockAgoraACLServer.startACLServer()
  }

  override def afterAll(): Unit = {
    MockAgoraACLServer.stopACLServer()
  }

  val localMethodPermissionsPath = s"/$localMethodsPath/permissions"


  // most of the functionality of this endpoint either exists in Agora or is unit-tested elsewhere.
  // here, we just test the routing and basic input/output of the endpoint.

  "Methods Repository multi-ACL upsert endpoint" - {
    "when testing DELETE, GET, POST methods on the multi-permissions path" - {
      "NotFound is returned" in {
        List(HttpMethods.DELETE, HttpMethods.GET, HttpMethods.POST) foreach {
          method =>
            new RequestBuilder(method)(localMethodPermissionsPath) ~> sealRoute(methodsApiServiceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    "when sending valid input" - {
      "returns OK and translates responses" in {
        val payload = Seq(
          MethodAclPair(MethodRepoMethod("ns1","n1",1), Seq(FireCloudPermission("user1@example.com","OWNER"))),
          MethodAclPair(MethodRepoMethod("ns2","n2",2), Seq(FireCloudPermission("user2@example.com","READER")))
        )
        Put(localMethodPermissionsPath, payload) ~> dummyAuthHeaders ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(OK)
          val resp = responseAs[Seq[MethodAclPair]]
          assert(resp.nonEmpty)
        }
      }
    }


    // BAD INPUTS
    "when posting malformed data" - {
      "BadRequest is returned" in {
        // endpoint expects a JsArray; send it a JsObject and expect BadRequest.
        Put(localMethodPermissionsPath, JsObject(Map("foo"->JsString("bar")))) ~> dummyAuthHeaders ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(BadRequest)
        }
      }
    }

  }
}
