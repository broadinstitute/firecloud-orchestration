package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{NIHStatus, UserInfo}
import org.broadinstitute.dsde.firecloud.service.NihService2.GetStatus
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import spray.http._
import spray.httpx.SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}


object NihService2 {
  sealed trait ServiceMessage
  case class GetStatus(userInfo: UserInfo) extends ServiceMessage

  def props(service: () => NihService2): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihService2(app.rawlsDAO, app.thurloeDAO)
}

class NihService2(val rawlsDao: RawlsDAO, val thurloeDao: ThurloeDAO)
  (implicit protected val executionContext: ExecutionContext) extends Actor
  with LazyLogging {

  override def receive = {
    case GetStatus(userInfo: UserInfo) => getStatus(userInfo) pipeTo sender
  }

  private def getStatus(userInfo: UserInfo): Future[PerRequestMessage] = {
    thurloeDao.getProfile(userInfo) flatMap {
      case Some(profile) =>
        profile.linkedNihUsername match {
          case Some(_) =>
            rawlsDao.isDbGapAuthorized(userInfo) map { x =>
              RequestComplete(NIHStatus(profile, Some(x)))
            }
          case None =>
            Future.successful(RequestComplete(StatusCodes.NotFound))
        }
      case None => Future.successful(RequestComplete(StatusCodes.NotFound))
    } pipeTo sender
  }
}
