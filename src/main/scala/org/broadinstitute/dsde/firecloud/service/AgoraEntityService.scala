package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.AgoraDAO
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{CopyPermissions, EditMethodRequest, EntityId}
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.AgoraEntityService.EditMethod
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import spray.http.StatusCode
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport

import scala.concurrent.{ExecutionContext, Future}

object AgoraEntityService {
  sealed trait AgoraEntityServiceMessage
  case class EditMethod(editMethod: EditMethodRequest)

  def props(agoraEntityServiceConstructor: UserInfo => AgoraEntityService, userInfo: UserInfo): Props = {
    Props(agoraEntityServiceConstructor(userInfo))
  }
}

class AgoraEntityService(protected val argUserInfo: UserInfo, val agoraDAO: AgoraDAO)(implicit protected val executionContext: ExecutionContext)
  extends Actor with SprayJsonSupport {

  implicit val userInfo = argUserInfo

  override def receive: Receive = {
    case EditMethod(editMethodRequest: EditMethodRequest) => { editMethod(editMethodRequest) } pipeTo sender
  }

  def editMethod(req: EditMethodRequest): Future[PerRequestMessage] = {
    val source = req.source
    val newMethod = agoraDAO.postMethod(source.namespace, source.name, req.synopsis, req.documentation, req.payload)
    newMethod flatMap { method =>
      val copyPermissions = CopyPermissions(source, EntityId(method.namespace.get, method.name.get, method.snapshotId.get))
      setMethodPermissions(copyPermissions) flatMap { _ =>
        if (req.redactOldSnapshot) {
          agoraDAO.redactMethod(source.namespace, source.name, source.snapshotId) map { _ =>
            RequestComplete(OK)
          } recover {
            case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, "Error while redacting old snapshot")
          }
        } else {
          Future(RequestComplete(OK))
        }
      } recover {
        case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, "Error while copying permissions")
      }
    } recover {
      case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, "Failed to create the new snapshot")
    }
  }

  def setMethodPermissions(copyPermissions: CopyPermissions): Future[PerRequestMessage] = {
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
