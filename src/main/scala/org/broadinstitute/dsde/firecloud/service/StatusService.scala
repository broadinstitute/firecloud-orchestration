package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.service.StatusService.CollectStatusInfo
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor.GetCurrentStatus
import org.broadinstitute.dsde.workbench.util.health.StatusCheckResponse
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport.StatusCheckResponseFormat
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Created by anichols on 4/5/17.
 */
object StatusService {
  def props(statusServiceConstructor: () => StatusService): Props = Props(statusServiceConstructor())

  def constructor(healthMonitor: ActorRef)()(implicit executionContext: ExecutionContext): StatusService = {
    new StatusService(healthMonitor)
  }

  case class CollectStatusInfo()
}

class StatusService (val healthMonitor: ActorRef)
                    (implicit protected val executionContext: ExecutionContext) extends Actor with SprayJsonSupport {
  implicit val timeout = Timeout(1.minute)


  override def receive: Receive = {
    case CollectStatusInfo => collectStatusInfo() pipeTo sender
  }

  def collectStatusInfo(): Future[PerRequestMessage] = {
    (healthMonitor ? GetCurrentStatus).mapTo[StatusCheckResponse].map { statusCheckResponse =>
      val httpStatus = if (statusCheckResponse.ok) StatusCodes.OK else StatusCodes.InternalServerError
      RequestComplete(httpStatus, statusCheckResponse)
    }
  }
}