package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.AgoraDAO
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.MethodsService.{GetConfiguration, MethodsServiceMessage}
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http.StatusCodes
import spray.httpx.SprayJsonSupport

import akka.pattern._

import scala.concurrent.{ExecutionContext, Future}


object MethodsService {

  sealed trait MethodsServiceMessage
  case class GetConfiguration(namespace: String, name: String, snapshot: Int) extends MethodsServiceMessage

  def props(methodsServiceConstructor: UserInfo => MethodsService, userInfo: UserInfo): Props = {
    Props(methodsServiceConstructor(userInfo))
  }

  def constructor(app: Application)(userInfo: UserInfo)(implicit executionContext: ExecutionContext) =
    new MethodsService(userInfo, app.agoraDAO)


}

class MethodsService (protected val argUserInfo: UserInfo, val agoraDAO: AgoraDAO) (implicit protected val executionContext: ExecutionContext) extends Actor {

  implicit val userInfo = argUserInfo

  override def receive: Receive = {
    case GetConfiguration(namespace: String, name: String, snapshot: Int) =>
      getConfigurationWithUnmarshalledPayload(namespace, name, snapshot) pipeTo sender
  }

  def getConfigurationWithUnmarshalledPayload(namespace: String, name: String, snapshotId: Int)(implicit userInfo: UserInfo): Future[PerRequestMessage] = {
    agoraDAO.getConfiguration(namespace, name, snapshotId) map { configResponse =>
      RequestComplete(StatusCodes.OK, "Hello world")
    }
  }
}
