package org.broadinstitute.dsde.firecloud.service

import akka.actor.Actor
import akka.actor.Props
import akka.pattern._
import org.broadinstitute.dsde.firecloud.Application
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
    new StatusService
  }

//  sealed trait StatusServiceMessage
  case class CollectStatusInfo() // extends StatusServiceMessage
}

class StatusService (implicit protected val executionContext: ExecutionContext) extends Actor with SprayJsonSupport {

  def collectStatusInfo(): Future[PerRequestMessage] = {
    Future(RequestComplete(SystemStatus("Hello world")))
  }

  def receive = {
    case CollectStatusInfo => collectStatusInfo() pipeTo sender
  }
}