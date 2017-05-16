package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.AgoraDAO
import org.broadinstitute.dsde.firecloud.model.MethodRepository.CopyPermissions
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.AgoraEntityService.CopyMethodPermissions
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport

import scala.concurrent.{ExecutionContext, Future}

object AgoraEntityService {
  sealed trait AgoraEntityServiceMessage
  case class CopyMethodPermissions(copyPermissions: CopyPermissions)

  def props(agoraEntityServiceConstructor: UserInfo => AgoraEntityService, userInfo: UserInfo): Props = {
    Props(agoraEntityServiceConstructor(userInfo))
  }
}

class AgoraEntityService(protected val argUserInfo: UserInfo, val agoraDAO: AgoraDAO)(implicit protected val executionContext: ExecutionContext)
  extends Actor with SprayJsonSupport {

  implicit val userInfo = argUserInfo

  override def receive: Receive = {
    case CopyMethodPermissions(copyPermissions: CopyPermissions) => { copyMethodPermissions(copyPermissions) } pipeTo sender
  }

  def copyMethodPermissions(copyPermissions: CopyPermissions): Future[PerRequestMessage] = {
    val (source, target) = (copyPermissions.source, copyPermissions.target)
    val resultPerms = for {
      sourcePerms <- agoraDAO.getMethodPermissions(source.namespace, source.name, source.snapshotId)
      resultPerms <- agoraDAO.postMethodPermissions(target.namespace, target.name, target.snapshotId, sourcePerms)
    } yield resultPerms

    resultPerms map {
      perms => RequestComplete(OK, perms map AgoraPermissionHandler.toFireCloudPermission)
    } recover {
      case e: FireCloudExceptionWithErrorReport =>
        RequestComplete(e.errorReport.statusCode.getOrElse(InternalServerError), e.errorReport)
      case e: Throwable =>
        RequestCompleteWithErrorReport(InternalServerError, e.getMessage)
    }
  }

}
