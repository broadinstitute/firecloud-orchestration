package org.broadinstitute.dsde.firecloud.service

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.{Application, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{RawlsDAO, SamDAO, ThurloeDAO, TrialDAO}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.service.RegisterService.{CreateUpdateProfile, UpdateProfilePreferences}
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
  case class UpdateProfilePreferences(userInfo: UserInfo, preferences: Map[String, String])
    extends ServiceMessage

  def props(service: () => RegisterService): Props = {
    Props(service())
  }

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new RegisterService(app.rawlsDAO, app.samDAO, app.thurloeDAO, app.trialDAO)
}

class RegisterService(val rawlsDao: RawlsDAO, val samDao: SamDAO, val thurloeDao: ThurloeDAO, val trialDao: TrialDAO)
  (implicit protected val executionContext: ExecutionContext) extends Actor
  with TrialServiceSupport with LazyLogging {

  override def receive = {
    case CreateUpdateProfile(userInfo, basicProfile) =>
      createUpdateProfile(userInfo, basicProfile) pipeTo sender
    case UpdateProfilePreferences(userInfo, preferences) =>
      updateProfilePreferences(userInfo, preferences) pipeTo sender
  }

  private def createUpdateProfile(userInfo: UserInfo, basicProfile: BasicProfile): Future[PerRequestMessage] = {
    for {
      _ <- thurloeDao.saveProfile(userInfo, basicProfile)
      _ <- thurloeDao.saveKeyValues(
          userInfo, Map("isRegistrationComplete" -> Profile.currentVersion.toString)
        )
      isRegistered <- samDao.getRegistrationStatus(userInfo) recover {
        case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.NotFound) =>
          RegistrationInfo(WorkbenchUserInfo(userInfo.id, userInfo.userEmail), WorkbenchEnabled(false, false, false))
      }
      userStatus <- if (!isRegistered.enabled.google || !isRegistered.enabled.ldap) {
        for {
          registrationInfo <- samDao.registerUser(userInfo)
          _ <- rawlsDao.registerUser(userInfo) //This call to rawls handles leftover registration pieces (welcome email and pending workspace access)
          freeCredits:Either[Exception,PerRequestMessage] <- enableSelfForFreeCredits(userInfo)
            .map(Right(_)) recover { case e: Exception => Left(e) }
        } yield registrationInfo
      } else Future.successful(isRegistered)
    } yield {
      RequestComplete(StatusCodes.OK, userStatus)
    }
  }

  private def updateProfilePreferences(userInfo: UserInfo, preferences: Map[String, String]): Future[PerRequestMessage] = {
    thurloeDao.saveKeyValues(userInfo, preferences).map(_ => RequestComplete(StatusCodes.NoContent))
  }

}
