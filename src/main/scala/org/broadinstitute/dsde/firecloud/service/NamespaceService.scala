package org.broadinstitute.dsde.firecloud.service

import akka.actor.Props
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.{AgoraPermissionActor, AgoraPermissionHandler}
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, FireCloudPermission}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.slf4j.LoggerFactory
import spray.http.{MediaTypes, StatusCodes}
import spray.httpx.SprayJsonSupport
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing._

trait NamespaceService extends HttpService with PerRequestCreator with FireCloudDirectives {

  lazy val log = LoggerFactory.getLogger(getClass)

  val routes: Route =
    pathPrefix("configurations|methods".r) { (agora_entity) =>
      // TODO: Stub for getting all namespace ACLs for the current user. See GAWB-1143
      path("permissions") {
        get {
          respondWithMediaType(MediaTypes.`application/json`) {
            complete("[]")
          }
        }
      } ~
      path(Segment / "permissions") { (namespace) =>
        val namespaceUrl = s"${FireCloudConfig.Agora.authUrl}/$agora_entity/$namespace/permissions"
        get { requestContext =>
          perRequest(requestContext,
            Props(new AgoraPermissionActor(requestContext)),
            AgoraPermissionHandler.Get(namespaceUrl))
        } ~
        post {
          entity(as[List[FireCloudPermission]]) { permissions => requestContext =>
            val agoraPermissions = permissions.map(permission => AgoraPermissionHandler.toAgoraPermission(permission))
            perRequest(
              requestContext,
              Props(new AgoraPermissionActor(requestContext)),
              AgoraPermissionHandler.Post(namespaceUrl, agoraPermissions))
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

  private def deleteQueryParams(permission: FireCloudPermission): String = {
    s"?user=${permission.user}"
  }

  private def putQueryParams(permission: AgoraPermission): String = {
    val user = permission.user.getOrElse("")
    val roles = permission.roles.getOrElse(List.empty).mkString(",")
    s"?user=$user&roles=$roles"
  }

}
