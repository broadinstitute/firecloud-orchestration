package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import org.broadinstitute.dsde.firecloud.dataaccess.ReportsSubsystemStatus
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impSystemStatus
import org.broadinstitute.dsde.firecloud.model.{SubsystemStatus, SystemStatus}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.StatusService.CollectStatusInfo
import org.broadinstitute.dsde.firecloud.{Application, FireCloudException, FireCloudExceptionWithErrorReport}
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport

import scala.concurrent.{ExecutionContext, Future}

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

  override def receive: Receive = {
    case CollectStatusInfo => collectStatusInfo() pipeTo sender
  }

  def collectStatusInfo(): Future[PerRequestMessage] = {

    val subsystemExceptionHandler: PartialFunction[Any, SubsystemStatus] = {
      case fcExceptionWithError: FireCloudExceptionWithErrorReport => SubsystemStatus(ok = false, Some(List(fcExceptionWithError.errorReport.message)))
      case fcException: FireCloudException => SubsystemStatus(ok = false, Some(List(fcException.getMessage)))
      case e: Exception => SubsystemStatus(ok = false, Some(List(e.getMessage)))
      case x: Any => SubsystemStatus(ok = false, Some(List(x.toString)))
    }

    val daoList = List[ReportsSubsystemStatus](app.rawlsDAO, app.thurloeDAO, app.agoraDAO, app.searchDAO, app.consentDAO, app.ontologyDAO)
    val futureStatusList: List[Future[(String, SubsystemStatus)]] = daoList map { dao =>
      dao.status.map { status =>
        dao.serviceName -> status
      }.recover {
        case t:Throwable => dao.serviceName -> subsystemExceptionHandler(t)
      }
    }
    Future.sequence(futureStatusList).
      map { fsl => fsl.toMap }.
      map { statusMap =>
        if (statusMap.values.forall(_.ok))
          RequestComplete(SystemStatus(ok = true, statusMap))
        else
          RequestComplete(StatusCodes.InternalServerError, SystemStatus(ok = false, statusMap))
    }
  }

}