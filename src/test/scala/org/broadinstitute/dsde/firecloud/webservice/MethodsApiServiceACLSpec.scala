package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.mock.MockAgoraACLData
import org.broadinstitute.dsde.firecloud.mock.MockAgoraACLData._
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impFireCloudPermission
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{AgoraPermissionService, BaseServiceSpec}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext


class MethodsApiServiceACLSpec extends BaseServiceSpec with MethodsApiService with SprayJsonSupport {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val agoraPermissionService: (UserInfo) => AgoraPermissionService = AgoraPermissionService.constructor(app)

  val localConfigPermissionsPath = localConfigsPath + "/ns/n/1/permissions"
  val localMethodPermissionsPath = localMethodsPath + "/ns/n/1/permissions"

  // we have to manually create this faulty json; we can't create it via FireCloudPermission objects, because
  // they don't allow faulty values!
  val sourceBadRole = """[{"user":"foo@broadinstitute.org","role":"OWNER"},{"user":"bar@broadinstitute.org","role":"UNKNOWN"}]"""
  val jsonBadRole = sourceBadRole.parseJson.asInstanceOf[JsArray]

  val sourceBadUser = """[{"user":"foo@broadinstitute.org","role":"OWNER"},{"user":"","role":"READER"}]"""
  val jsonBadUser = sourceBadUser.parseJson.asInstanceOf[JsArray]

    /* ACL endpoints.
       We unit test the individual translations, so we only need to augment the unit tests here. We need to test:
        * handling of lists (unit tests work with single objects)
        * configs vs. methods paths are not mixed up (we use a single code path for both)
        * rejections when invalid data POSTed
        * DELETE/PUT methods are rejected (we supersede those with POST, and should block passthroughs)
     */
  "MethodsServiceACLs" - {
    "when testing DELETE, PUT methods on the permissions paths" - {
      "MethodNotAllowed is returned" in {
        List(HttpMethods.DELETE, HttpMethods.PUT) map {
          method =>
            new RequestBuilder(method)("/" + localMethodPermissionsPath) ~> sealRoute(methodsApiServiceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
            new RequestBuilder(method)("/" + localConfigPermissionsPath) ~> sealRoute(methodsApiServiceRoutes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

    // BAD INPUTS
    "when posting bad roles to methods" - {
      "BadRequest is returned" in {
        Post("/" + localMethodPermissionsPath, jsonBadRole) ~> dummyAuthHeaders ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(BadRequest)
        }
      }
    }
    "when posting bad roles to configs" - {
      "BadRequest is returned" in {
        Post("/" + localConfigPermissionsPath, jsonBadRole) ~> dummyAuthHeaders ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(BadRequest)
        }
      }
    }
    "when posting bad users to methods" - {
      "BadRequest is returned" in {
        Post("/" + localMethodPermissionsPath, jsonBadUser) ~> dummyAuthHeaders ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(BadRequest)
        }
      }
    }
    "when posting bad users to configs" - {
      "BadRequest is returned" in {
        Post("/" + localConfigPermissionsPath, jsonBadUser) ~> dummyAuthHeaders ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(BadRequest)
        }
      }
    }

    // LISTS ARE TRANSLATED PROPERLY
    // configuration endpoints return the mock data in the proper order
    "when retrieving ACLs from configs" - {
      "the entire list is successfully translated" in {
        Get("/" + localConfigsPath + MockAgoraACLData.standardPermsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(OK)
          var perms = responseAs[List[FireCloudPermission]]
          perms shouldBe standardFC
        }
      }
    }
    // methods endpoints return the mock data in reverse order - this way we can differentiate methods vs. configs
    "when retrieving ACLs from methods" - {
      "the entire (reversed) list is successfully translated" in {
        Get("/" + localMethodsPath + MockAgoraACLData.standardPermsPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(OK)
          var perms = responseAs[List[FireCloudPermission]]
          perms shouldBe standardFC.reverse
        }
      }
    }

    // AGORA RETURNS FAULTY DATA
    "when retrieving bad Agora data from configs" - {
      "InternalServerError is returned" in {
        Get("/" + localConfigsPath + MockAgoraACLData.withEdgeCasesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(InternalServerError)
        }
      }
    }
    "when retrieving bad Agora data from methods" - {
      "InternalServerError is returned" in {
        Get("/" + localMethodsPath + MockAgoraACLData.withEdgeCasesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(InternalServerError)
        }
      }
    }

    // POSTS
    // configs endpoint returns good data from Agora on post
    "when posting good data to configs, expecting a good response" - {
      "a good response is returned" in {
        Post("/" + localConfigsPath + MockAgoraACLData.standardPermsPath, standardFC) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(OK)
          var perms = responseAs[List[FireCloudPermission]]
          perms shouldBe standardFC
        }
      }
    }
    // methods endpoint returns faulty data from Agora on post
    "when posting good data to methods, expecting an invalid response" - {
      "an invalid response is returned and we throw an error" in {
        Post("/" + localMethodsPath + MockAgoraACLData.standardPermsPath, standardFC) ~> dummyUserIdHeaders("1234") ~> sealRoute(methodsApiServiceRoutes) ~> check {
          status should equal(InternalServerError)
        }
      }
    }
  }
}
