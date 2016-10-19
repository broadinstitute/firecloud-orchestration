package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{ACLNames, AgoraPermission, FireCloudPermission}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.webservice.NamespaceApiService
import org.mockserver.model.HttpRequest._
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._

class NamespaceApiServiceSpec extends BaseServiceSpec with NamespaceApiService {

  val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)

  def actorRefFactory = system

  val urls = List("/api/methods/namespace/permissions", "/api/configurations/namespace/permissions")

  val agoraPermission = AgoraPermission(
    user = Some("random@site.com"),
    roles = Some(ACLNames.ListOwner)
  )

  val fcPermissions = List(AgoraPermissionHandler.toFireCloudPermission(agoraPermission))

  "NamespaceApiService" - {

    "when calling GET on a namespace permissions path" - {
      "a valid list of FireCloud permissions is returned" in {
        urls map {
          url =>
            Get(url) ~> dummyUserIdHeaders("1234") ~> sealRoute(namespaceRoutes) ~> check {
              status should equal(OK)
              val permission = responseAs[List[FireCloudPermission]]
              permission shouldNot be (None)
            }
        }
      }
    }

    "when calling POST on a namespace permissions path" - {
      "a valid FireCloud permission is returned" in {
        urls map {
          url =>
            Post(url, fcPermissions) ~> dummyUserIdHeaders("1234") ~> sealRoute(namespaceRoutes) ~> check {
              status should equal(OK)
              val permission = responseAs[List[FireCloudPermission]]
              permission shouldNot be (None)
            }
        }
      }
    }

    "when calling PUT or DELETE on a namespace permissions path" - {
      "a Method Not Allowed response is returned" in {
        urls map {
          url =>
            List(HttpMethods.PUT, HttpMethods.DELETE) map {
              method =>
                new RequestBuilder(method)(url, fcPermissions) ~> dummyUserIdHeaders("1234") ~> sealRoute(namespaceRoutes) ~> check {
                  status should equal(MethodNotAllowed)
                }
            }
        }
      }
    }

  }

}
