package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.server.{Directives, Route}
import org.broadinstitute.dsde.firecloud.model.OrchMethodRepository.FireCloudPermission
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.NamespaceService
import org.broadinstitute.dsde.firecloud.utils.StandardUserInfoDirectives
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait NamespaceApiService extends Directives with RequestBuilding with StandardUserInfoDirectives {

  implicit val executionContext: ExecutionContext

  val namespaceServiceConstructor: UserInfo => NamespaceService

  val namespaceRoutes: Route =
    pathPrefix("api" / "methods|configurations".r / Segment / "permissions") { (agoraEntity, namespace) =>
      requireUserInfo() { userInfo =>
        get {
          complete { namespaceServiceConstructor(userInfo).getFireCloudPermissions(namespace, agoraEntity) }
        } ~
          post {
            // explicitly pull in the json-extraction error handler from ModelJsonProtocol
            handleRejections(entityExtractionRejectionHandler) {
              entity(as[List[FireCloudPermission]]) { permissions =>
                complete { namespaceServiceConstructor(userInfo).postFireCloudPermissions(namespace, agoraEntity, permissions) }
              }
            }
          }
      }
    }
}
