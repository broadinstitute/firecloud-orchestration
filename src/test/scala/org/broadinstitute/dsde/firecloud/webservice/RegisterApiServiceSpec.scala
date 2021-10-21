package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.dataaccess.MockThurloeDAO
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, RegisterService}
import akka.http.scaladsl.model.StatusCodes.{BadRequest, NoContent, OK}
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.FireCloudApiService
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import spray.json.DefaultJsonProtocol.jsonFormat12

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

final class RegisterApiServiceSpec extends BaseServiceSpec with RegisterApiService
  with DefaultJsonProtocol with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override val registerServiceConstructor:() => RegisterService =
    RegisterService.constructor(app.copy(thurloeDAO = new RegisterApiServiceSpecThurloeDAO))

  "RegisterApiService" - {
    "update-preferences API" - {

      "should succeed with a single notifications key" in {
        val payload = Map(
          "notifications/foo" -> "yes"
        )
        assertPreferencesUpdate(payload, NoContent)
      }

      "should succeed with multiple notifications keys" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" ->  "no",
          "notifications/baz" -> "astring"
        )
        assertPreferencesUpdate(payload, NoContent)
      }

      "should refuse with a single disallowed key" in {
        val payload = Map(
          "abadkey" -> "astring"
        )
        assertPreferencesUpdate(payload, BadRequest)
      }

      "should refuse with mixed notifications and disallowed keys" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" ->  "no",
          "secretsomething" -> "astring"
        )
        assertPreferencesUpdate(payload, BadRequest)
      }

      "should refuse with the string 'notifications/' in the middle of a key" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" ->  "no",
          "substring/notifications/arebad" -> "true"
        )
        assertPreferencesUpdate(payload, BadRequest)
      }

      "should succeed with an empty payload" in {
        val payload = Map.empty[String, String]
        assertPreferencesUpdate(payload, NoContent)
      }

    }

    "register-profile API" - {

      val RANDOM_STRING = MockUtils.randomAlpha()
      val BASIC_PROFILE = BasicProfile(
        firstName = RANDOM_STRING,
        lastName = RANDOM_STRING,
        title = RANDOM_STRING,
        contactEmail = Some("me@abc.com"),
        institute = RANDOM_STRING,
        institutionalProgram = RANDOM_STRING,
        programLocationCity = RANDOM_STRING,
        programLocationState = RANDOM_STRING,
        programLocationCountry = RANDOM_STRING,
        pi = RANDOM_STRING,
        nonProfitStatus = RANDOM_STRING,
        termsOfService = Some("app.terra.bio/#terms-of-service")
      )
      implicit val impBasicProfile: RootJsonFormat[BasicProfile] = jsonFormat12(BasicProfile)

      "should succeed with no terms of service" in {
        val payload = BasicProfile(
          firstName = RANDOM_STRING,
          lastName = RANDOM_STRING,
          title = RANDOM_STRING,
          contactEmail = Some("me@abc.com"),
          institute = RANDOM_STRING,
          institutionalProgram = RANDOM_STRING,
          programLocationCity = RANDOM_STRING,
          programLocationState = RANDOM_STRING,
          programLocationCountry = RANDOM_STRING,
          pi = RANDOM_STRING,
          nonProfitStatus = RANDOM_STRING,
          termsOfService = None
        )
        Post("/register/profile", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec") ~> sealRoute(registerRoutes) ~> check {
          status should be(OK)
        }
      }

      "should succeed with terms of service" in {
        Post("/register/profile", BASIC_PROFILE) ~> dummyUserIdHeaders("RegisterApiServiceSpec") ~> sealRoute(registerRoutes) ~> check {
          status should be(OK)
        }
      }
    }
  }

  private def assertPreferencesUpdate(payload: Map[String, String], expectedStatus: StatusCode): Unit = {
    Post("/profile/preferences", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec") ~> sealRoute(profileRoutes) ~> check {
      status should be(expectedStatus)
    }
  }

  // for purposes of these tests, we treat Thurloe as if it is always successful.
  final class RegisterApiServiceSpecThurloeDAO extends MockThurloeDAO {
    override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String])= Future.successful(Success(()))
  }

}

