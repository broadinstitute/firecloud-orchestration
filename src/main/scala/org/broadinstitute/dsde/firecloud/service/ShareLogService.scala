package org.broadinstitute.dsde.firecloud.service

import akka.pattern.pipe
import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impShareFormat
import org.broadinstitute.dsde.firecloud.dataaccess.ShareLogDAO
import org.broadinstitute.dsde.firecloud.model.ShareLog.ShareType
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.ShareLogService._
import org.broadinstitute.dsde.rawls.model.{AttributeFormat, PlainArrayAttributeListSerializer}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object ShareLogService {
  sealed trait ShareLogMessage
  case class LogShare(userId: String, sharee: String, shareType: ShareType.Value) extends ShareLogMessage
  case class GetShares(userId: String, shareType: Option[ShareType.Value] = None) extends ShareLogMessage
  case class GetSharees(userId: String, shareType: Option[ShareType.Value] = None) extends ShareLogMessage

  def props(constructor: () => ShareLogService): Props = {
    Props(constructor())
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    () => new ShareLogService(userInfo, app.shareLogDAO)
}

class ShareLogService(protected val userInfo: UserInfo, val shareDAO: ShareLogDAO)
                     (implicit protected val executionContext: ExecutionContext) extends Actor with SprayJsonSupport {

  implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

  override def receive = {
    case LogShare(userId: String, sharee: String, shareType: ShareType.Value) => logShare(userId, sharee, shareType) pipeTo sender
    case GetShares(userId: String, shareType: Option[ShareType.Value]) => getShares(userId) pipeTo sender
    case GetSharees(userId: String, shareType: Option[ShareType.Value]) => getSharees(userId, shareType) pipeTo sender
  }

  def logShare(userId: String, sharee: String, shareType: ShareType.Value) = Future(RequestComplete(shareDAO.logShare(userId, sharee, shareType)))

  def getShares(userId: String, shareType: Option[ShareType.Value] = None) = {
    Future(RequestComplete(shareDAO.getShares(userId, shareType)))
  }

  def getSharees(userId: String, shareType: Option[ShareType.Value] = None) = {
    Future(RequestComplete(shareDAO.getShares(userId, shareType).flatMap(_.sharee)))
  }
}
