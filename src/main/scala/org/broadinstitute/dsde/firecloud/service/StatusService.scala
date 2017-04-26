package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import akka.actor.Props
import akka.pattern._
import org.broadinstitute.dsde.firecloud.{Application, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{AgoraDAO, RawlsDAO, SearchDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impSystemStatus
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, SystemStatus}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.StatusService.CollectStatusInfo
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext

/**
 * Created by anichols on 4/5/17.
 */
object StatusService {
  def props(statusServiceConstructor: () => StatusService): Props = Props(statusServiceConstructor())

  def constructor(app: Application)()(implicit executionContext: ExecutionContext): StatusService = {
    new StatusService(app)
  }

  case class CollectStatusInfo()
}

class StatusService (val app: Application)
                    (implicit protected val executionContext: ExecutionContext) extends Actor with SprayJsonSupport {

  override def receive = {
    case CollectStatusInfo => collectStatusInfo() pipeTo sender
  }

  def collectStatusInfo(): Future[PerRequestMessage] = {
    val subsystemExceptionHandler: PartialFunction[Any, SubsystemStatus] = {
      case fcExceptionWithError: FireCloudExceptionWithErrorReport => SubsystemStatus(false, Some(List(fcExceptionWithError.errorReport.message)))
      case fcException: FireCloudException => SubsystemStatus(false, Some(List(fcException.getMessage)))
      case e: Exception => SubsystemStatus(false, Some(List(e.getMessage)))
      case x: Any => SubsystemStatus(false, Some(List(x.toString)))
    }

    for {
      rawlsStatus <- app.rawlsDAO.status recover subsystemExceptionHandler
      thurloeStatus <- app.thurloeDAO.status recover subsystemExceptionHandler
      agoraStatus <- app.agoraDAO.status recover subsystemExceptionHandler
      searchStatus <- app.searchDAO.status recover subsystemExceptionHandler
    } yield {
      val statusMap = Map(RawlsDAO.serviceName -> rawlsStatus, ThurloeDAO.serviceName -> thurloeStatus, AgoraDAO.serviceName -> agoraStatus, SearchDAO.serviceName -> searchStatus)

      if (statusMap.values.forall(_.ok))
        RequestComplete(SystemStatus(true, statusMap))
      else
        RequestComplete(StatusCodes.InternalServerError, SystemStatus(false, statusMap))

    }
  }

}