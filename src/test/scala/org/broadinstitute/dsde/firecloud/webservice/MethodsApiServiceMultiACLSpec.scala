package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impMethodAclPair
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{AgoraPermissionService, BaseServiceSpec, ServiceSpec}
import org.broadinstitute.dsde.rawls.model.MethodRepoMethod
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext


class MethodsApiServiceMultiACLSpec extends BaseServiceSpec with ServiceSpec with MethodsApiService with SprayJsonSupport {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val agoraPermissionService: (UserInfo) => AgoraPermissionService = AgoraPermissionService.constructor(app)

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
        Put(localMethodPermissionsPath, payload) ~> dummyUserIdHeaders("MethodsApiServiceMultiACLSpec") ~> sealRoute(methodsApiServiceRoutes) ~> check {
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
