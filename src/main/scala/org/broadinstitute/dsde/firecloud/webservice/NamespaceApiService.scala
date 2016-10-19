package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, NamespaceService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.routing._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext

trait NamespaceApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives
  with StandardUserInfoDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  val namespaceServiceConstructor: UserInfo => NamespaceService

  val namespaceRoutes: Route =
    pathPrefix("api" / "methods|configurations".r / Segment / "permissions") { (agoraEntity, namespace) =>
      requireUserInfo() { userInfo =>
        get { requestContext =>
          perRequest(requestContext,
            NamespaceService.props(namespaceServiceConstructor, userInfo),
            NamespaceService.GetPermissions(namespace, "configurations"))
        } ~
        post {
          entity(as[List[FireCloudPermission]]) { permissions => requestContext =>
            perRequest(
              requestContext,
              NamespaceService.props(namespaceServiceConstructor, userInfo),
              NamespaceService.PostPermissions(namespace, "configurations", permissions))
          }
        }
      }
    }

}
