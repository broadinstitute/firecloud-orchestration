package org.broadinstitute.dsde.firecloud.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.{Application, FireCloudConfig, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.model.Notifications.{ActivationNotification, AzurePreviewActivationNotification, AzurePreviewActivationNotificationType, Notification, NotificationFormat}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.firecloud.FireCloudConfig.Sam
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

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

  def createUserWithProfile(userInfo: UserInfo, registerRequest: RegisterRequest): Future[PerRequestMessage] =
    for {
      registerResult <- registerUser(userInfo, registerRequest.acceptsTermsOfService)
      // We are using the equivalent value from sam registration to force the order of operations for the thurloe calls
      registrationResultUserInfo  = userInfo.copy(userEmail = registerResult.email.value, id = registerResult.id.value)
      _ <- saveProfileInThurloeAndSendRegistrationEmail(registrationResultUserInfo, registerRequest.profile)
    } yield RequestComplete(StatusCodes.OK, registerResult)

  def createUpdateProfile(userInfo: UserInfo, basicProfile: BasicProfile): Future[PerRequestMessage] = {
    for {
      isRegistered <- isRegistered(userInfo)
      userStatus <- if (!isRegistered.enabled.google || !isRegistered.enabled.ldap) {
        for {
          registerResult <- registerUser(userInfo, basicProfile.termsOfService)
          registrationResultUserInfo  = userInfo.copy(userEmail = registerResult.userInfo.userEmail, id = registerResult.userInfo.userSubjectId)
          _ <- saveProfileInThurloeAndSendRegistrationEmail(registrationResultUserInfo, basicProfile)
        } yield registerResult
      } else {
        /* when updating the profile in Thurloe, make sure to send the update under the same user id as the profile
           was originally created with. A given use can have a Sam id, a Google subject id, and a b2c Azure id; if we
           send profile updates under a different id, Thurloe will create duplicate keys for the user.

           Because the original profile was created during registration using `userInfo.userSubjectId` (see
           `registrationResultUserInfo` above), we use that same id here.
         */
        thurloeDao.saveProfile(userInfo.copy(id = isRegistered.userInfo.userSubjectId), basicProfile) map (_ => isRegistered)
      }
    } yield {
      RequestComplete(StatusCodes.OK, userStatus)
    }
  }

  private def saveProfileInThurloeAndSendRegistrationEmail(userInfo: UserInfo, profile: BasicProfile): Future[Unit] = {
    val otherValues = Map("isRegistrationComplete" -> Profile.currentVersion.toString, "email" -> userInfo.userEmail)
    thurloeDao.saveProfile(userInfo, profile).andThen {
      case Success(_) => thurloeDao.saveKeyValues(userInfo, otherValues) andThen {
        case Success(_) => googleServicesDAO.publishMessages(FireCloudConfig.Notification.fullyQualifiedNotificationTopic, Seq(NotificationFormat.write(generateWelcomeEmail(userInfo)).compactPrint))
      }
    }
  }

  private def isRegistered(userInfo: UserInfo): Future[RegistrationInfo] = {
    samDao.getRegistrationStatus(userInfo) recover {
      case e: FireCloudExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.NotFound) =>
        RegistrationInfo(WorkbenchUserInfo(userInfo.id, userInfo.userEmail), WorkbenchEnabled(false, false, false))
    }
  }

  def generateWelcomeEmail(userInfo: UserInfo): Notification = {
    //If the user is a B2C user and does not have a Google access token, we can safely assume that they're an Azure user
    userInfo.googleAccessTokenThroughB2C match {
      case None if userInfo.isB2C => AzurePreviewActivationNotification(WorkbenchUserId(userInfo.id))
      case _ => ActivationNotification(WorkbenchUserId(userInfo.id))
    }
  }

  private def registerUser(userInfo: UserInfo, termsOfService: Option[String]): Future[RegistrationInfo] =
    samDao.registerUser(termsOfService)(userInfo)

  private def registerUser(userInfo: UserInfo, acceptsTermsOfService: Boolean): Future[SamUserResponse] =
    samDao.registerUserSelf(acceptsTermsOfService)(userInfo)

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
