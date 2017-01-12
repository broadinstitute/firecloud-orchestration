package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{Profile, UserInfo}
import org.broadinstitute.dsde.firecloud.service.NihService.GetStatus
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import spray.http._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}


case class NihStatus(
  loginRequired: Boolean,
  linkedNihUsername: Option[String] = None,
  isDbgapAuthorized: Option[Boolean] = None,
  lastLinkTime: Option[Long] = None,
  linkExpireTime: Option[Long] = None,
  descriptionSinceLastLink: Option[String] = None,
  descriptionUntilExpires: Option[String] = None
)

object NihStatus {

  implicit val impNihStatus = jsonFormat7(NihStatus.apply)

  def apply(profile: Profile): NihStatus = {
    apply(profile, profile.isDbgapAuthorized)
  }

  def apply(profile: Profile, isDbGapAuthorized: Option[Boolean]): NihStatus = {
    val linkExpireSeconds = profile.linkExpireTime.getOrElse(0L)
    val howSoonExpire = DateUtils.secondsSince(linkExpireSeconds)
    new NihStatus(
      loginRequired = howSoonExpire >= 0,
      profile.linkedNihUsername,
      isDbGapAuthorized,
      profile.lastLinkTime,
      profile.linkExpireTime
    )
  }
}

object NihService {
  sealed trait ServiceMessage
  case class GetStatus(userInfo: UserInfo) extends ServiceMessage

  def props(service: () => NihService): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new NihService(app.rawlsDAO, app.thurloeDAO)
}

class NihService(val rawlsDao: RawlsDAO, val thurloeDao: ThurloeDAO)
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
              RequestComplete(NihStatus(profile, Some(x)))
            }
          case None =>
            Future.successful(RequestComplete(StatusCodes.NotFound))
        }
      case None => Future.successful(RequestComplete(StatusCodes.NotFound))
    }
  }
}
