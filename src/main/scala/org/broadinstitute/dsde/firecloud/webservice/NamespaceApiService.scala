package org.broadinstitute.dsde.firecloud.webservice

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, NamespaceService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.http.StatusCodes
import spray.routing._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext

trait NamespaceApiService extends HttpService with FireCloudRequestBuilding with FireCloudDirectives
  with LazyLogging with StandardUserInfoDirectives {

  private implicit val ec: ExecutionContext = actorRefFactory.dispatcher

  val namespaceServiceConstructor: UserInfo => NamespaceService

  val namespaceRoutes: Route =
    pathPrefix("configurations|methods".r) { (agoraEntity) =>
      requireUserInfo() { userInfo =>
        path(Segment / "permissions") { (namespace) =>

          val namespaceUrl = s"${FireCloudConfig.Agora.authUrl}/$agoraEntity/$namespace/permissions"

          get { requestContext =>
            logger.debug("Get: " + requestContext.request.toString)
            perRequest(requestContext,
              NamespaceService.props(namespaceServiceConstructor, userInfo),
              NamespaceService.GetPermissions(namespace, agoraEntity))
          } ~
          post {
            entity(as[List[FireCloudPermission]]) { permissions => requestContext =>
              logger.debug("Post: " + requestContext.request.toString)
              perRequest(
                requestContext,
                NamespaceService.props(namespaceServiceConstructor, userInfo),
                NamespaceService.PostPermissions(namespaceUrl, agoraEntity, permissions))
            }
          } ~
          // Put and delete support are unnecessary.
          // Post will perform insert, update, and delete operations on each change
          delete {
            complete(StatusCodes.MethodNotAllowed)
          } ~
          put {
            complete(StatusCodes.MethodNotAllowed)
          }
        }
      }
    }

}
