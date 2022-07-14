package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.dataaccess.MockThurloeDAO
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, RegisterService}
import akka.http.scaladsl.model.StatusCodes.{BadRequest, Forbidden, NoContent, OK}
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.HealthChecks.termsOfServiceUrl
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impBasicProfile
import spray.json.DefaultJsonProtocol

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

      "should succeed with multiple valid preference keys" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" -> "no",
          "notifications/baz" -> "astring",
          "starredWorkspaces" -> "fooId,barId,bazId"
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
          "notifications/bar" -> "no",
          "secretsomething" -> "astring"
        )
        assertPreferencesUpdate(payload, BadRequest)
      }

      "should refuse with the string 'notifications/' in the middle of a key" in {
        val payload = Map(
          "notifications/foo" -> "yes",
          "notifications/bar" -> "no",
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
      "should fail with no terms of service" in {
        val payload = makeBasicProfile(false)
        Post("/register/profile", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec", "new") ~> sealRoute(registerRoutes) ~> check {
          status should be(Forbidden)
        }
      }

      "should succeed with terms of service" in {
        val payload = makeBasicProfile(true)
        Post("/register/profile", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec", "new") ~> sealRoute(registerRoutes) ~> check {
          status should be(OK)
        }
      }

      "should succeed user who already exists" in {
        val payload = makeBasicProfile(true)
        Post("/register/profile", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec") ~> sealRoute(registerRoutes) ~> check {
          status should be(OK)
        }
      }

      def makeBasicProfile(hasTermsOfService: Boolean): BasicProfile = {
        val randomString = MockUtils.randomAlpha()
        BasicProfile(
          firstName = randomString,
          lastName = randomString,
          title = randomString,
          contactEmail = Some("me@abc.com"),
          institute = randomString,
          institutionalProgram = randomString,
          programLocationCity = randomString,
          programLocationState = randomString,
          programLocationCountry = randomString,
          pi = randomString,
          nonProfitStatus = randomString,
          termsOfService = if (hasTermsOfService) Some(termsOfServiceUrl) else None
        )
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

