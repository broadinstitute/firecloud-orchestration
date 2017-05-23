package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.core.AgoraPermissionHandler
import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, AgoraException}
import org.broadinstitute.dsde.firecloud.model.MethodRepository.{EditMethodRequest, EditMethodResponse, MethodId}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.AgoraEntityService.EditMethod
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object AgoraEntityService {
  sealed trait AgoraEntityServiceMessage
  case class EditMethod(editMethod: EditMethodRequest)

  def props(agoraEntityServiceConstructor: UserInfo => AgoraEntityService, userInfo: UserInfo): Props = {
    Props(agoraEntityServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new AgoraEntityService(userInfo, app.agoraDAO)
}

class AgoraEntityService(protected val argUserInfo: UserInfo, val agoraDAO: AgoraDAO)(implicit protected val executionContext: ExecutionContext)
  extends Actor with SprayJsonSupport {

  implicit val userInfo = argUserInfo

  override def receive: Receive = {
    case EditMethod(editMethodRequest: EditMethodRequest) => { editMethod(editMethodRequest) } pipeTo sender
  }

  def editMethod(req: EditMethodRequest): Future[PerRequestMessage] = {
    val source = req.source
    agoraDAO.postMethod(source.namespace, source.name, req.synopsis, req.documentation, req.payload) flatMap { newMethod =>
      val newId = MethodId(newMethod.namespace.get, newMethod.name.get, newMethod.snapshotId.get)
      setMethodPermissions(source, newId) flatMap { _ =>
        if (req.redactOldSnapshot) {
          agoraDAO.redactMethod(source.namespace, source.name, source.snapshotId) map { _ =>
            RequestComplete(OK, EditMethodResponse(newMethod))
          } recover {
            case _ =>
              val msg = "The new snapshot was created, but there was an error while redacting the previous snapshot."
              RequestComplete(OK, EditMethodResponse(newMethod, Some(msg)))
          }
        } else {
          Future(RequestComplete(OK, EditMethodResponse(newMethod)))
        }
      // remove all these inner recovers ...
      } recover {
        case _ =>
          val msg = "The new snapshot was created, but there was an error while copying permissions." +
            (if (req.redactOldSnapshot) " The previous snapshot was not redacted." else "")
          RequestComplete(OK, EditMethodResponse(newMethod, Some(msg)))
      }
    } recover {
      case ae: AgoraException => ae.method match {
        case "postMethod" => // here is where we handle errors posting the method
          RequestCompleteWithErrorReport(InternalServerError, "Failed to create the new snapshot", ae)
        case "permissionsGet" => // etc etc
          RequestComplete(OK, "some other thing")
        case _ => RequestComplete(OK, "default case")
      }
      case e: Throwable => RequestCompleteWithErrorReport(InternalServerError, "Failed to create the new snapshot", e)
    }
  }

  def setMethodPermissions(source: MethodId, target: MethodId): Future[PerRequestMessage] = {
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
