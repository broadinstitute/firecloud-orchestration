package org.broadinstitute.dsde.firecloud.service

import akka.pattern.pipe
import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impShare, _}
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.ShareLogDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.ShareLogService.{Autocomplete, GetShares, LogShare}
import org.broadinstitute.dsde.rawls.model.{AttributeFormat, PlainArrayAttributeListSerializer}

import scala.concurrent.{ExecutionContext, Future}

object ShareLogService {
  sealed trait ShareLogMessage
  case class LogShare(userId: String, sharee: String, shareType: String) extends ShareLogMessage
  case class GetShares(userId: String) extends ShareLogMessage
  case class Autocomplete(userId: String, term: String) extends ShareLogMessage

  def props(constructor: () => ShareLogService): Props = {
    Props(constructor())
  }

//  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) = new ShareLogService(userInfo, app.shareLogDAO)

}

class ShareLogService(protected val userInfo: UserInfo, val shareDAO: ShareLogDAO)
                     (implicit protected val executionContext: ExecutionContext) extends Actor {

  implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

  override def receive = {
    case LogShare(userId: String, sharee: String, shareType: String) => logShare(userId, sharee, shareType) pipeTo sender
    case GetShares(userId: String) => getShares(userId) pipeTo sender
    case Autocomplete(userId: String, term: String) => autocomplete(userId, term) pipeTo sender
  }

  def logShare(userId: String, sharee: String, shareType: String) = Future(RequestComplete(shareDAO.logShare(userId, sharee, shareType)))

  def getShares(userId: String) = Future(RequestComplete(shareDAO.getShares(userId)))

  def autocomplete(userId: String, term: String): Future[PerRequestMessage] = Future(RequestComplete(shareDAO.autocomplete(userId, term)))
}
