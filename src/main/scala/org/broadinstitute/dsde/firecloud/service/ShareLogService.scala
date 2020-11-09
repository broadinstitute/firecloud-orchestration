package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.ShareLogDAO
import org.broadinstitute.dsde.firecloud.model.ShareLog.ShareType
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.{AttributeFormat, PlainArrayAttributeListSerializer}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object ShareLogService {
  def constructor(app: Application)(implicit executionContext: ExecutionContext) =
    () => new ShareLogService(app.shareLogDAO)
}

class ShareLogService(val shareLogDAO: ShareLogDAO)(implicit protected val executionContext: ExecutionContext) extends SprayJsonSupport {

  implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer

  def GetSharees(userId: String, shareType: Option[ShareType.Value]) = getSharees(userId, shareType)

  def getSharees(userId: String, shareType: Option[ShareType.Value] = None) = Future(RequestComplete(shareLogDAO.getShares(userId, shareType).map(_.sharee)))
}
