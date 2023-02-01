package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.firecloud.dataaccess.{GoogleServicesDAO, RawlsDAO, SamDAO, ThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.workbench.model.Notifications.{ActivationNotification, ActivationNotificationType, AzurePreviewActivationNotification, AzurePreviewActivationNotificationType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock

class RegisterServiceSpec extends AnyFlatSpec with Matchers {

  import scala.concurrent.ExecutionContext.Implicits.global

  val rawlsDAO = mock[RawlsDAO]
  val samDAO = mock[SamDAO]
  val thurloeDAO = mock[ThurloeDAO]
  val googleServicesDAO = mock[GoogleServicesDAO]

  val registerService = new RegisterService(rawlsDAO, samDAO, thurloeDAO, googleServicesDAO)

  val azureB2CUserInfo = UserInfo("azure-b2c@example.com", OAuth2BearerToken("token"), 1, "0f3cd8e4-59c2-4bce-9c24-98c5a0c308c1", None)
  val googleB2CUserInfo = UserInfo("google-b2c@example.com", OAuth2BearerToken("token"), 1, "0617047d-a81f-4724-b783-b5af51af9a70", Some(OAuth2BearerToken("some-google-token")))
  val googleLegacyUserInfo = UserInfo("google-legacy@example.com", OAuth2BearerToken("token"), 1, "111111111111", None)

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

}
