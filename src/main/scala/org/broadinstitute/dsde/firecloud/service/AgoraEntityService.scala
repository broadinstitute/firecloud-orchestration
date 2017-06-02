package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.Application
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
  case class EditMethod(editMethod: EditMethodRequest) extends AgoraEntityServiceMessage

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
      copyMethodPermissions(source, newId) flatMap { _ =>
        if (req.redactOldSnapshot.getOrElse(false)) {
          agoraDAO.redactMethod(source.namespace, source.name, source.snapshotId) map { _ =>
            RequestComplete(OK, EditMethodResponse(newMethod))
          } recover {
            case e: Exception => RequestCompleteWithErrorReport(NonAuthoritativeInformation, "Error occurred while redacting previous snapshot", e)
          }
        } else {
          Future(RequestComplete(OK, EditMethodResponse(newMethod)))
        }
      } recover {
        case e: Exception => RequestCompleteWithErrorReport(NonAuthoritativeInformation, "Error occurred while copying permissions", e)
      }
    } recover {
      case e: Exception => RequestCompleteWithErrorReport(InternalServerError, "An internal error occurred on method edit", e)
    }
  }

  private def copyMethodPermissions(source: MethodId, target: MethodId) = {
    for {
      sourcePerms <- agoraDAO.getMethodPermissions(source.namespace, source.name, source.snapshotId)
      resultPerms <- agoraDAO.postMethodPermissions(target.namespace, target.name, target.snapshotId, sourcePerms)
    } yield resultPerms

  }

}
