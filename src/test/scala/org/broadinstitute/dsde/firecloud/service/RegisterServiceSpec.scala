package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.firecloud.HealthChecks.termsOfServiceUrl
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, RegistrationInfo, UserInfo, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.workbench.model.Notifications.{ActivationNotification, AzurePreviewActivationNotification}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Await, Future}
import scala.util.Success

class RegisterServiceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  import scala.concurrent.ExecutionContext.Implicits.global

  val rawlsDAO: RawlsDAO = mock[RawlsDAO]
  val samDAO: SamDAO = mock[SamDAO]
  val thurloeDAO: ThurloeDAO = mock[ThurloeDAO]
  val googleServicesDAO: GoogleServicesDAO = mock[GoogleServicesDAO]

  val registerService = new RegisterService(rawlsDAO, samDAO, thurloeDAO, googleServicesDAO)

  val azureB2CUserInfo: UserInfo = UserInfo("azure-b2c@example.com", OAuth2BearerToken("token"), 1, "0f3cd8e4-59c2-4bce-9c24-98c5a0c308c1", None)
  val googleB2CUserInfo: UserInfo = UserInfo("google-b2c@example.com", OAuth2BearerToken("token"), 1, "0617047d-a81f-4724-b783-b5af51af9a70", Some(OAuth2BearerToken("some-google-token")))
  val googleLegacyUserInfo: UserInfo = UserInfo("google-legacy@example.com", OAuth2BearerToken("token"), 1, "111111111111", None)

  val profile: BasicProfile = BasicProfile(
    firstName = "first",
    lastName = "last",
    title = "title",
    contactEmail = Some("me@abc.com"),
    institute = "inst",
    researchArea = Some("research"),
    programLocationCity = "city",
    programLocationState = "state",
    programLocationCountry = "country",
    termsOfService = Some(termsOfServiceUrl),
    department = Some("dept"),
    interestInTerra = Some("interest")
  )

  override protected def beforeEach(): Unit = {
    reset(samDAO)
    reset(thurloeDAO)
    super.beforeEach()
  }

  "generateWelcomeEmail" should "generate an Azure welcome email for Azure B2C users" in {
    val notification = registerService.generateWelcomeEmail(azureB2CUserInfo)
    notification.getClass.getName shouldBe AzurePreviewActivationNotification.getClass.getName.stripSuffix("$")
  }

  it should "generate a standard welcome email for Google B2C users" in {
    val notification = registerService.generateWelcomeEmail(googleB2CUserInfo)
    notification.getClass.getName shouldBe ActivationNotification.getClass.getName.stripSuffix("$")
  }

  it should "generate a standard welcome email for legacy Google users" in {
    val notification = registerService.generateWelcomeEmail(googleLegacyUserInfo)
    notification.getClass.getName shouldBe ActivationNotification.getClass.getName.stripSuffix("$")
  }

  behavior of "createUpdateProfile"

  List(azureB2CUserInfo, googleB2CUserInfo, googleLegacyUserInfo) foreach { userInfo =>
    it should s"both register and save profile for unregistered user ${userInfo.userEmail}" in {
      // ARRANGE
      // user is not registered; registration check returns google=false and ldap=false
      when(samDAO.getRegistrationStatus(userInfo)).thenReturn(
        Future.successful(
          RegistrationInfo(
            WorkbenchUserInfo(userInfo.id, userInfo.userEmail),
            WorkbenchEnabled(google = false, ldap = false, allUsersGroup = false),
            None)))
      // registering this user returns successfully
      when(samDAO.registerUser(any())(ArgumentMatchers.eq(userInfo))).thenReturn(Future.successful(
        RegistrationInfo(
          WorkbenchUserInfo(userInfo.id, userInfo.userEmail),
          WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true),
          None)))
      // saving to Thurloe returns successfully
      when(thurloeDAO.saveProfile(userInfo, profile)).thenReturn(Future.successful(()))
      when(thurloeDAO.saveKeyValues(ArgumentMatchers.eq(userInfo), any())).thenReturn(Future.successful(Success(())))

      // ACT
      // call createUpdateProfile for this previously-unregistered user
      Await.result(registerService.createUpdateProfile(userInfo, profile), Duration(10, SECONDS))

      // ASSERT
      // createUpdateProfile should invoke both SamDAO.registerUser and ThurloeDAO.saveProfile
      verify(samDAO, times(1)).registerUser(any())(ArgumentMatchers.eq(userInfo))
      verify(thurloeDAO, times(1)).saveProfile(userInfo, profile)
    }

    it should s"save profile but not register for registered user ${userInfo.userEmail}" in {
      // ARRANGE
      // user is already registered; registration check returns google=true and ldap=true
      when(samDAO.getRegistrationStatus(userInfo)).thenReturn(
        Future.successful(
          RegistrationInfo(
            WorkbenchUserInfo(userInfo.id, userInfo.userEmail),
            WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true),
            None)))
      // saving to Thurloe returns successfully
      when(thurloeDAO.saveProfile(userInfo, profile)).thenReturn(Future.successful(()))
      when(thurloeDAO.saveKeyValues(ArgumentMatchers.eq(userInfo), any())).thenReturn(Future.successful(Success(())))

      // ACT
      // call createUpdateProfile for this previously-unregistered user
      Await.result(registerService.createUpdateProfile(userInfo, profile), Duration(10, SECONDS))

      // ASSERT
      // createUpdateProfile should not invoke SamDAO.registerUser, but should invoke ThurloeDAO.saveProfile
      verify(samDAO, never()).registerUser(any())(ArgumentMatchers.eq(userInfo))
      verify(thurloeDAO, times(1)).saveProfile(userInfo, profile)
    }
  }

}
