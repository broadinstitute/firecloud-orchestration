package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import akka.actor.Props
import akka.pattern._
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, RawlsDAO, SearchDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, SystemStatus}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.StatusService.CollectStatusInfo
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext

/**
 * Created by anichols on 4/5/17.
 */
object StatusService {
  def props(statusServiceConstructor: () => StatusService): Props = Props(statusServiceConstructor())

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
      rawlsStatus <- rawlsDAO.status
      thurloeStatus <- thurloeDAO.status
      agoraStatus <- agoraDAO.status
      searchStatus <- searchDAO.status
    } yield {
      val statusMap = Map("Rawls" -> rawlsStatus, "Thurloe" -> thurloeStatus, "Agora" -> agoraStatus, "Search" -> searchStatus)

      RequestComplete(SystemStatus(statusMap.values.count(_.ok == true) == 0, statusMap))
    }
  }

  def receive = {
    case CollectStatusInfo => collectStatusInfo() pipeTo sender
  }
}