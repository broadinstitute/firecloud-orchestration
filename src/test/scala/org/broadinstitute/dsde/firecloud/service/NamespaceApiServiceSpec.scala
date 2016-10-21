package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.MockAgoraDAO
import org.broadinstitute.dsde.firecloud.model.MethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.webservice.NamespaceApiService

import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

class NamespaceApiServiceSpec extends BaseServiceSpec with NamespaceApiService {

  val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)

  def actorRefFactory = system

  val urls = List("/api/methods/namespace/permissions", "/api/configurations/namespace/permissions")

  val fcPermissions = List(AgoraPermissionHandler.toFireCloudPermission(MockAgoraDAO.agoraPermission))

  "NamespaceApiService" - {

    "when calling GET on a namespace permissions path" - {
      "a valid list of FireCloud permissions is returned" in {
        urls map {
          url =>
            Get(url) ~> dummyUserIdHeaders("1234") ~> sealRoute(namespaceRoutes) ~> check {
              status should equal(OK)
              val permissions = responseAs[List[FireCloudPermission]]
              permissions should be (fcPermissions)
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
              val permissions = responseAs[List[FireCloudPermission]]
              permissions should be (fcPermissions)
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
