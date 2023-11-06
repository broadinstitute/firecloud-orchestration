package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.pattern._
import akka.util.Timeout
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor.GetCurrentStatus
import org.broadinstitute.dsde.workbench.util.health.StatusCheckResponse
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport.StatusCheckResponseFormat

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Created by anichols on 4/5/17.
  */
object StatusService {
  def constructor(healthMonitor: ActorRef)()(implicit executionContext: ExecutionContext): StatusService = {
    new StatusService(healthMonitor)
  }
}

class StatusService (val healthMonitor: ActorRef)
                    (implicit protected val executionContext: ExecutionContext) extends SprayJsonSupport {
  implicit val timeout: Timeout = Timeout(1.minute) // timeout for the ask to healthMonitor for GetCurrentStatus

  def collectStatusInfo(): Future[PerRequestMessage] = {
    (healthMonitor ? GetCurrentStatus).mapTo[StatusCheckResponse].map { statusCheckResponse =>
      // if we've successfully reached this point, always return a 200, so the load balancers
      // don't think orchestration is down. the statusCheckResponse will still contain ok: true|false
      // in its payload, depending on the status of subsystems.
      RequestComplete(StatusCodes.OK, statusCheckResponse)
    }
  }
}
