package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.AgoraDAO
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, FireCloudPermission}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.NamespaceService.{GetPermissions, PostPermissions}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object NamespaceService {
  case class GetPermissions(ns: String, entity: String)
  case class PostPermissions(ns: String, entity: String, permissions: List[FireCloudPermission])

  def props(namespaceService: UserInfo => NamespaceService, userInfo: UserInfo): Props = {
    Props(namespaceService(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new NamespaceService(userInfo, app.agoraDAO)
}

class NamespaceService (protected val argUserInfo: UserInfo, val agoraDAO: AgoraDAO)(implicit protected val executionContext: ExecutionContext)
  extends Actor with SprayJsonSupport with LazyLogging {

  implicit val userInfo = argUserInfo

  override def receive = {
    case GetPermissions(ns: String, entity: String) => { getFireCloudPermissions(ns, entity) } pipeTo sender
    case PostPermissions(ns: String, entity: String, permissions: List[FireCloudPermission]) => { postFireCloudPermissions(ns, entity, permissions) } pipeTo sender
  }

  def getFireCloudPermissions(ns: String, entity: String): Future[PerRequestMessage] = {
    val fcPermissions = convertPermissions(agoraDAO.getNamespacePermissions(ns, entity))
    Future(RequestComplete(OK, fcPermissions))
  }

  def postFireCloudPermissions(ns: String, entity: String, permissions: List[FireCloudPermission]): Future[PerRequestMessage] = {
    val agoraPermissions = permissions map { permission => AgoraPermissionHandler.toAgoraPermission(permission) }
    val fcPermissions = convertPermissions(agoraDAO.postNamespacePermissions(ns, entity, agoraPermissions))
    Future(RequestComplete(OK, fcPermissions))
  }

  private def convertPermissions(agoraPerms: Future[List[AgoraPermission]]): Future[List[FireCloudPermission]] = {
    agoraPerms map {
      permissions =>
        permissions.map {
          permission =>
            AgoraPermissionHandler.toFireCloudPermission(permission)
        }
    }
  }

}
