package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.dataaccess.{MockSamDAO, MockThurloeDAO}
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, RegisterRequest, UserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, RegisterService, UserService}
import akka.http.scaladsl.model.StatusCodes.{BadRequest, Forbidden, NoContent, OK}
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.HealthChecks.termsOfServiceUrl
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impBasicProfile
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impRegisterRequest
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

final class RegisterApiServiceSpec
    extends BaseServiceSpec
    with RegisterApiService
    with UserApiService
    with DefaultJsonProtocol
    with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override val registerServiceConstructor: () => RegisterService =
    RegisterService.constructor(app.copy(thurloeDAO = new RegisterApiServiceSpecThurloeDAO))

  override val userServiceConstructor: (UserInfo) => UserService =
    UserService.constructor(app.copy(thurloeDAO = new RegisterApiServiceSpecThurloeDAO))

  def makeBasicProfile(hasTermsOfService: Boolean): BasicProfile = {
    val randomString = MockUtils.randomAlpha()
    BasicProfile(
      firstName = randomString,
      lastName = randomString,
      title = randomString,
      contactEmail = Some("me@abc.com"),
      institute = randomString,
      researchArea = Some(randomString),
      programLocationCity = randomString,
      programLocationState = randomString,
      programLocationCountry = randomString,
      termsOfService = if (hasTermsOfService) Some(termsOfServiceUrl) else None,
      department = Some(randomString),
      interestInTerra = Some(randomString)
    )
  }

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

    "register-profile API POST" - {
      "should fail with no terms of service" in {
        val payload = makeBasicProfile(false)
        Post("/register/profile", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec", "new") ~> sealRoute(
          registerRoutes
        ) ~> check {
          status should be(Forbidden)
        }
      }

      "should succeed with terms of service" in {
        val payload = makeBasicProfile(true)
        Post("/register/profile", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec", "new") ~> sealRoute(
          registerRoutes
        ) ~> check {
          status should be(OK)
        }
      }

      "should succeed user who already exists" in {
        val payload = makeBasicProfile(true)
        Post("/register/profile", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec") ~> sealRoute(
          registerRoutes
        ) ~> check {
          status should be(OK)
        }
      }
    }

    "register-with-profile API POST" - {
      "should fail if Sam does not register the user" in {
        val payload = makeBasicProfile(false)
        Post("/users/v1/registerWithProfile",
             RegisterRequest(acceptsTermsOfService = false, profile = payload)
        ) ~> dummyUserIdHeaders("RegisterApiServiceSpec", "new") ~> sealRoute(v1RegisterRoutes) ~> check {
          status should be(BadRequest)
        }
      }

      "should succeed if Sam does register the user" in {
        val payload = makeBasicProfile(true)
        Post("/users/v1/registerWithProfile",
             RegisterRequest(acceptsTermsOfService = true, profile = payload)
        ) ~> dummyUserIdHeaders("RegisterApiServiceSpec", "new") ~> sealRoute(v1RegisterRoutes) ~> check {
          status should be(OK)
        }
      }
    }

    "register-profile API GET" - {
      // Because this endpoint is used during the registration process, it
      // should always return info about the user; if it blocked unregistered
      // or disabled users, those users would not be able to proceed.
      //
      // These tests will fail if GET /register/profile is put behind requireEnabledUser().
      List("enabled", MockSamDAO.disabledUserToken, MockSamDAO.unregisteredUserToken) foreach { testCase =>
        s"should succeed for a(n) $testCase user" in
          Get("/register/profile") ~> dummyUserIdHeaders(userId = testCase, token = testCase) ~> sealRoute(
            userServiceRoutes
          ) ~> check {
            withClue(s"with actual response body: ${responseAs[String]}, got error message ->") {
              status should be(OK)
            }
          }
      }
    }
  }

  private def assertPreferencesUpdate(payload: Map[String, String], expectedStatus: StatusCode): Unit =
    Post("/profile/preferences", payload) ~> dummyUserIdHeaders("RegisterApiServiceSpec") ~> sealRoute(
      profileRoutes
    ) ~> check {
      status should be(expectedStatus)
    }

  // for purposes of these tests, we treat Thurloe as if it is always successful.
  final class RegisterApiServiceSpecThurloeDAO extends MockThurloeDAO {
    override def saveKeyValues(userInfo: UserInfo, keyValues: Map[String, String]) = Future.successful(Success(()))
  }
}
