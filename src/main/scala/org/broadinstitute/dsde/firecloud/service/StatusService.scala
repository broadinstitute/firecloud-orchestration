package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import akka.actor.Props
import akka.pattern._
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SearchDAO, AgoraDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.SystemStatus
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.StatusService.CollectStatusInfo
import spray.httpx.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impStatus

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

//    https://agora.dsde-dev.broadinstitute.org/status

    thurloeDAO.status.flatMap { case (status, message) =>
      if (status) {
        Future(RequestComplete(SystemStatus("Thurloe is up")))
      } else {
        Future(RequestComplete(SystemStatus("Problem with Thurloe: $message")))
      }
    }

    agoraDAO.status.flatMap { case (status, message) =>
      if (status) {
        Future(RequestComplete(SystemStatus("Agora is up")))
      } else {
        Future(RequestComplete(SystemStatus(s"Agora is down: $message")))
      }
    }

    if (searchDAO.indexExists()) {
      Future(RequestComplete(SystemStatus("Search is up")))
    } else {
      Future(RequestComplete(SystemStatus("Problem with search")))
    }
  }

  def receive = {
    case CollectStatusInfo => collectStatusInfo() pipeTo sender
  }
}