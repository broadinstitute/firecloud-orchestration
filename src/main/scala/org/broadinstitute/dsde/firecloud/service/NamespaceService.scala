package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import org.broadinstitute.dsde.firecloud.dataaccess.AgoraDAO
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{AgoraPermission, FireCloudPermission}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impFireCloudPermission
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudExceptionWithErrorReport}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object NamespaceService {
  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new NamespaceService(userInfo, app.agoraDAO)
}

class NamespaceService (protected val argUserInfo: UserInfo, val agoraDAO: AgoraDAO)(implicit protected val executionContext: ExecutionContext)
  extends SprayJsonSupport {

  implicit val userInfo = argUserInfo

  def getFireCloudPermissions(ns: String, entity: String): Future[PerRequestMessage] = {
    val agoraPermissions = agoraDAO.getNamespacePermissions(ns, entity)
    delegatePermissionsResponse(agoraPermissions)
  }

  def postFireCloudPermissions(ns: String, entity: String, permissions: List[FireCloudPermission]): Future[PerRequestMessage] = {
    val agoraPermissionsToPost = permissions map { permission => AgoraPermissionService.toAgoraPermission(permission) }
    val agoraPermissionsPosted = agoraDAO.postNamespacePermissions(ns, entity, agoraPermissionsToPost)
    delegatePermissionsResponse(agoraPermissionsPosted)
  }

  private def delegatePermissionsResponse(agoraPerms: Future[List[AgoraPermission]]): Future[PerRequestMessage] = {
    agoraPerms map {
      perms =>
        RequestComplete(OK, perms map AgoraPermissionService.toFireCloudPermission)
    } recover {
      case e: FireCloudExceptionWithErrorReport =>
//        RequestComplete(e.errorReport.statusCode.getOrElse(InternalServerError), e.errorReport)
        RequestComplete(e.errorReport.statusCode.getOrElse(InternalServerError))
      case e: Throwable =>
        RequestCompleteWithErrorReport(InternalServerError, e.getMessage)
    }
  }

}
