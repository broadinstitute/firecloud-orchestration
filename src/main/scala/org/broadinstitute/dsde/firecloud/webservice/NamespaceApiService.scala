package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.MethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.{ModelJsonProtocol, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{FireCloudDirectives, FireCloudRequestBuilding, NamespaceService}
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext

trait NamespaceApiService extends FireCloudRequestBuilding with FireCloudDirectives
  with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  val namespaceServiceConstructor: UserInfo => NamespaceService

  val namespaceRoutes: Route =
    pathPrefix("api" / "methods|configurations".r / Segment / "permissions") { (agoraEntity, namespace) =>
      requireUserInfo() { userInfo =>
        get {
          complete { namespaceServiceConstructor(userInfo).GetPermissions(namespace, agoraEntity) }
        } ~
          post {
            // explicitly pull in the json-extraction error handler from ModelJsonProtocol
            handleRejections(entityExtractionRejectionHandler) {
              entity(as[List[FireCloudPermission]]) { permissions =>
                complete { namespaceServiceConstructor(userInfo).PostPermissions(namespace, agoraEntity, permissions) }
              }
            }
          }
      }
    }
}
