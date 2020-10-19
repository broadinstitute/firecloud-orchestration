package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.MockAgoraDAO
import org.broadinstitute.dsde.firecloud.model.MethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.webservice.NamespaceApiService
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes._
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.server.Route.{seal => sealRoute}

import scala.concurrent.ExecutionContext

class NamespaceApiServiceSpec(override val executionContext: ExecutionContext) extends BaseServiceSpec with NamespaceApiService {

  val namespaceServiceConstructor: (UserInfo) => NamespaceService = NamespaceService.constructor(app)

  def actorRefFactory = system

  val urls = List("/api/methods/namespace/permissions", "/api/configurations/namespace/permissions")

  val fcPermissions = List(AgoraPermissionService.toFireCloudPermission(MockAgoraDAO.agoraPermission))

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
