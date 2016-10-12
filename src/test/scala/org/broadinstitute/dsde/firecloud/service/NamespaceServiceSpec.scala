package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{ACLNames, AgoraPermission, FireCloudPermission}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._

class NamespaceServiceSpec extends ServiceSpec with NamespaceService {

  def actorRefFactory = system

  var methodsServer: ClientAndServer = _

  val permission1 = AgoraPermission(
    user = Some("test-user@broadinstitute.org"),
    roles = Some(ACLNames.ListOwner)
  )

  val permission2 = AgoraPermission(
    user = Some("test-user2@broadinstitute.org"),
    roles = Some(ACLNames.ListNoAccess)
  )

  val permissions = List(permission1, permission2)

  val remoteUrls = List("/api/v1/methods/name-space/permissions", "/api/v1/configurations/name-space/permissions")

  override def beforeAll(): Unit = {
    methodsServer = startClientAndServer(methodsServerPort)
    remoteUrls.map {
      url =>
        methodsServer
          .when(request()
            .withMethod("GET")
            .withPath(url))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
              .withBody(permissions.toJson.toString)
          )

        methodsServer
          .when(
            request()
              .withMethod("DELETE")
              .withPath(url)
              .withQueryStringParameter("user", "random@site.com"))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
              .withBody(
                AgoraPermission(user = Some("random@site.com"), roles = Some(List.empty)).toJson.toString)
          )

        // Pretty Print body content is required for this test since spray Post sends over a
        // pretty-print version of the HttpEntity passed into it.
        methodsServer
          .when(
            request()
              .withMethod("POST")
              .withPath(url)
              .withBody(permissions.toJson.prettyPrint))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
              .withBody(permissions.toJson.toString)
          )

    }
  }

  override def afterAll(): Unit = {
    methodsServer.stop()
  }

  val localUrls = List("/methods/name-space/permissions", "/configurations/name-space/permissions")
  val invalidLocalUrls = List("/methods/invalid/permissions", "/configurations/invalid/permissions")
  val logRequest: RequestTransformer = { r => println(r); r }

  val deletePermission = FireCloudPermission(user = "random@site.com", role = "OWNER")
  val fcPermissions = List(
    AgoraPermissionHandler.toFireCloudPermission(permission1),
    AgoraPermissionHandler.toFireCloudPermission(permission2)
  )

  "NamespaceService" - {

    "when calling GET on a namespace permissions path" - {
      "a valid list of FireCloud permissions is returned" in {
        localUrls.map {
          url =>
            Get(url) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
              status should equal(OK)
              val permission = responseAs[List[FireCloudPermission]]
              permission shouldNot be (None)
            }
        }
      }
    }

    "when calling GET on an invalid namespace permissions path" - {
      "a Not Found error is returned" in {
        invalidLocalUrls.map {
          url =>
            Get(url) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
              status should be (NotFound)
            }
        }
      }
    }

    "when calling Delete on a namespace permissions path" - {
      "a FireCloud permission with No Access is returned" in {
        localUrls.map {
          url =>
            Delete(url, deletePermission) ~> dummyUserIdHeaders("random@site.com") ~> sealRoute(routes) ~> check {
              status should equal(OK)
              val permission = responseAs[FireCloudPermission]
              permission shouldNot be (None)
              permission.role should be (ACLNames.NoAccess)
            }
        }
      }
    }

    "when calling Delete on an invalid namespace permissions path" - {
      "a Not Found response is returned" in {
        invalidLocalUrls.map {
          url =>
            Delete(url, deletePermission) ~> dummyUserIdHeaders("random@site.com") ~> sealRoute(routes) ~> check {
              status should equal(NotFound)
            }
        }
      }
    }

    "when calling POST on a namespace permissions path" - {
      "a valid FireCloud permission is returned" in {
        localUrls.map {
          url =>
            Post(url, fcPermissions) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
              status should equal(OK)
              val permission = responseAs[List[FireCloudPermission]]
              permission shouldNot be (None)
            }
        }
      }
    }

    "when calling POST on an invalid namespace permissions path" - {
      "a Not Found response is returned" in {
        invalidLocalUrls.map {
          url =>
            Post(url, fcPermissions) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
              status should equal(NotFound)
            }
        }
      }
    }

    "when calling PUT on a namespace permissions path" - {
      "a Method Not Allowed response is returned" in {
        invalidLocalUrls.map {
          url =>
            Put(url, fcPermissions) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
              status should equal(MethodNotAllowed)
            }
        }
      }
    }

  }

}
