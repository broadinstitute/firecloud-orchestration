package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import akka.actor.Props
import akka.pattern._
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, RawlsDAO, SearchDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.SystemStatus
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.StatusService.CollectStatusInfo
import spray.httpx.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impStatus

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext

/**
 * Created by anichols on 4/5/17.
 */
object StatusService {
  def props(statusServiceConstructor: () => StatusService): Props = {
    Props(statusServiceConstructor())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext): StatusService = {
    new StatusService(app.searchDAO, app.agoraDAO, app.thurloeDAO, app.rawlsDAO)
  }

  case class CollectStatusInfo()
}

class StatusService (val searchDAO: SearchDAO,
                     val agoraDAO: AgoraDAO,
                     val thurloeDAO: ThurloeDAO,
                     val rawlsDAO: RawlsDAO)
                    (implicit protected val executionContext: ExecutionContext) extends Actor with SprayJsonSupport {

  def collectStatusInfo(): Future[PerRequestMessage] = {
    for {
      rawlsOK <- rawlsDAO.status
      (thurloeOK, thurloeMessage) <- thurloeDAO.status
      (agoraOK, agoraMessage) <- agoraDAO.status
      searchOK <- Future(searchDAO.indexExists())
    } yield {
      val allOK = rawlsOK && thurloeOK && agoraOK && searchOK
      var messages: ListBuffer[String] = ListBuffer()

      if (!rawlsOK) messages += "Problem with Rawls"
      if (!thurloeOK) messages += "Problem with Thurloe: " + thurloeMessage.getOrElse("(No further information available)")
      if (!agoraOK) messages += "Problem with Agora: " + agoraMessage.getOrElse("(No further information available)")
      if (!searchOK) messages += "Problem with Search"

      RequestComplete(SystemStatus(allOK, messages.mkString("; ")))
    }
  }

  def receive = {
    case CollectStatusInfo => collectStatusInfo() pipeTo sender
  }
}