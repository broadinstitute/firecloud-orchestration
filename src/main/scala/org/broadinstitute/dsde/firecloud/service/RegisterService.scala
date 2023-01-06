package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.model.Notifications.{ActivationNotification, NotificationFormat}
import org.broadinstitute.dsde.rawls.model.{ErrorReport}
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Sam
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId

import scala.concurrent.{ExecutionContext, Future}

object RegisterService {
  val samTosTextUrl = s"${Sam.baseUrl}/tos/text"
  val samTosBaseUrl = s"${Sam.baseUrl}/register/user/v1/termsofservice"
  val samTosStatusUrl = s"${samTosBaseUrl}/status"
  val samTosDetailsUrl = s"${Sam.baseUrl}/register/user/v2/self/termsOfServiceDetails"

  def constructor(app: Application)()(implicit executionContext: ExecutionContext) =
    new RegisterService(app.rawlsDAO, app.samDAO, app.thurloeDAO, app.googleServicesDAO)

}

class RegisterService(val rawlsDao: RawlsDAO, val samDao: SamDAO, val thurloeDao: ThurloeDAO, val googleServicesDAO: GoogleServicesDAO)
  (implicit protected val executionContext: ExecutionContext) extends LazyLogging {

  def createUpdateProfile(userInfo: UserInfo, basicProfile: BasicProfile): Future[PerRequestMessage] = {
    for {
      _ <- thurloeDao.saveProfile(userInfo, basicProfile)
      _ <- thurloeDao.saveKeyValues(userInfo, Map("isRegistrationComplete" -> Profile.currentVersion.toString))
      isRegistered <- isRegistered(userInfo)
      userStatus <- if (!isRegistered.enabled.google || !isRegistered.enabled.ldap) {
        for {
          _ <- thurloeDao.saveKeyValues(userInfo,  Map("email" -> userInfo.userEmail))
          registerResult <- registerUser(userInfo, basicProfile.termsOfService)
        } yield registerResult
      } else {
        Future.successful(isRegistered)
      }
    } yield {
      RequestComplete(StatusCodes.OK, userStatus)
    }
  }

  private def isRegistered(userInfo: UserInfo): Future[RegistrationInfo] = {
    samDao.getRegistrationStatus(userInfo) recover {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.NotFound) =>
        RegistrationInfo(WorkbenchUserInfo(userInfo.id, userInfo.userEmail), WorkbenchEnabled(false, false, false))
    }
  }

  private def registerUser(userInfo: UserInfo, termsOfService: Option[String]): Future[RegistrationInfo] = {
    for {
      registrationInfo <- samDao.registerUser(termsOfService)(userInfo)
      _ <- googleServicesDAO.publishMessages(FireCloudConfig.Notification.fullyQualifiedNotificationTopic, Seq(NotificationFormat.write(ActivationNotification(WorkbenchUserId(userInfo.id))).compactPrint))
    } yield {
      registrationInfo
    }
  }

  //  utility method to determine if a preferences key headed to Thurloe is valid for user input.
  private def isValidPreferenceKey(key: String): Boolean = {
    val validKeyPrefixes = FireCloudConfig.Thurloe.validPreferenceKeyPrefixes
    val validKeys = FireCloudConfig.Thurloe.validPreferenceKeys

    validKeyPrefixes.exists(prefix => key.startsWith(prefix)) || validKeys.contains(key)
  }

  def updateProfilePreferences(userInfo: UserInfo, preferences: Map[String, String]): Future[PerRequestMessage] = {
    if (preferences.keys.forall(isValidPreferenceKey)) {
      thurloeDao.saveKeyValues(userInfo, preferences).map(_ => RequestComplete(StatusCodes.NoContent))
    } else {
      throw new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "illegal preference key"))
    }
  }
}
