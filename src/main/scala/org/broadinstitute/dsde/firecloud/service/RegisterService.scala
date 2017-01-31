package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.RegisterService.CreateUpdateProfile
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

object RegisterService {
  sealed trait ServiceMessage
  case class CreateUpdateProfile(userInfo: UserInfo, basicProfile: BasicProfile)
    extends ServiceMessage

  def props(service: () => RegisterService): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new RegisterService(app.rawlsDAO, app.thurloeDAO)
}

class RegisterService(val rawlsDao: RawlsDAO, val thurloeDao: ThurloeDAO)
  (implicit protected val executionContext: ExecutionContext) extends Actor
  with LazyLogging {

  override def receive = {
    case CreateUpdateProfile(userInfo, basicProfile) =>
      createUpdateProfile(userInfo, basicProfile) pipeTo sender
  }

  private def createUpdateProfile(userInfo: UserInfo, basicProfile: BasicProfile): Future[PerRequestMessage] = {
    thurloeDao.saveProfile(userInfo, basicProfile) flatMap { _ =>
      thurloeDao.saveKeyValue(
          userInfo, "isRegistrationComplete", Profile.currentVersion.toString
        ) flatMap { _ =>
          rawlsDao.isRegistered(userInfo) flatMap {
            case true => Future.successful(RequestComplete(StatusCodes.OK))
            case false => rawlsDao.registerUser(userInfo) map { _ =>
              thurloeDao.sendNotifications(List(ActivationNotification(userInfo.id)))
              RequestComplete(StatusCodes.OK)
            }
          }
        }
    }
  }
}
