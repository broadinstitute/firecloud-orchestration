package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import akka.http.scaladsl.unmarshalling.Unmarshal
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, SamMockserverUtils}
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, RegisterService, UserService}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

class UserApiServiceSpec extends BaseServiceSpec with SamMockserverUtils
  with RegisterApiService with UserApiService with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val registerServiceConstructor:() => RegisterService = RegisterService.constructor(app)
  val userServiceConstructor:(UserInfo) => UserService = UserService.constructor(app)
  var workspaceServer: ClientAndServer = _
  var profileServer: ClientAndServer = _
  var samServer: ClientAndServer = _
  val httpMethods = List(HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT,
    HttpMethods.DELETE, HttpMethods.PATCH, HttpMethods.OPTIONS, HttpMethods.HEAD)

  val userWithGoogleGroup = "have-google-group"
  val userWithEmptyGoogleGroup = "have-empty-google-group"
  val userWithNoContactEmail = "no-contact-email"
  val uniqueId = "normal-user"
  val exampleKey = "favoriteColor"
  val exampleVal = "green"
  val fullProfile = BasicProfile(
    firstName= randomAlpha(),
    lastName = randomAlpha(),
    title = randomAlpha(),
    contactEmail = None,
    institute = randomAlpha(),
    researchArea = Some(randomAlpha()),
    programLocationCity = randomAlpha(),
    programLocationState = randomAlpha(),
    programLocationCountry = randomAlpha(),
    termsOfService = None
  )
  val allProperties: Map[String, String] = fullProfile.propertyValueMap

  val userStatus = """{
                     |  "userInfo": {
                     |    "userSubjectId": "1234567890",
                     |    "userEmail": "user@gmail.com"
                     |  },
                     |  "enabled": {
                     |    "google": true,
                     |    "allUsersGroup": true,
                     |    "ldap": true
                     |  }
                     |}""".stripMargin

  val enabledV1UserBody = """{"enabled": {"google": true, "ldap": true, "allUsersGroup": true}, "userInfo": {"userSubjectId": "1111111111", "userEmail": "no@nope.org"}}"""
  val noLdapV1UserBody = """{"enabled": {"google": true, "ldap": false, "allUsersGroup": true}, "userInfo": {"userSubjectId": "1111111111", "userEmail": "no@nope.org"}}"""
  val noGoogleV1UserBody = """{"enabled": {"google": false, "ldap": true, "allUsersGroup": true}, "userInfo": {"userSubjectId": "1111111111", "userEmail": "no@nope.org"}}"""

  val enabledV2UserBody = """{"userSubjectId": "1111111111", "userEmail": "no@nope.org", "enabled": true}"""
  val noLdapV2UserBody = """{"userSubjectId": "1111111111", "userEmail": "no@nope.org", "enabled": false}"""

  val enabledV2DiagnosticsBody = """{"enabled": true, "inAllUsersGroup": true, "inGoogleProxyGroup": true}"""
  val noLdapV2DiagnosticsBody = """{"enabled": false, "inAllUsersGroup": true, "inGoogleProxyGroup": true}"""
  val noGoogleV2DiagnosticsBody = """{"enabled": true, "inAllUsersGroup": true, "inGoogleProxyGroup": false}"""

  val uglyJsonBody = """{"userInfo": "whaaaaaaat??"}"""


  override def beforeAll(): Unit = {

    workspaceServer = startClientAndServer(workspaceServerPort)
    workspaceServer
      .when(request.withMethod("GET").withPath(UserApiService.billingPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    samServer = startClientAndServer(samServerPort)
    samServer
      .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    samServer
      .when(request.withMethod("POST").withPath(UserApiService.samRegisterUserPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(Created.intValue)
      )

    samServer
      .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody(userStatus).withStatusCode(OK.intValue)
      )

    samServer
      .when(request.withMethod("GET").withPath(UserApiService.samUserProxyGroupPath("test@test.test")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    returnEnabledUser(samServer)

    profileServer = startClientAndServer(thurloeServerPort)
    // Generate a mock response for all combinations of profile properties
    // to ensure that all posts to any combination will yield a successful response.
    allProperties.keys foreach {
      key =>
        profileServer
          .when(request().withMethod("POST").withHeader(fireCloudHeader.name, fireCloudHeader.value).withPath(
            UserApiService.remoteGetKeyPath.format(uniqueId, key)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }

    List(HttpMethods.GET, HttpMethods.POST, HttpMethods.DELETE) foreach {
      method =>
        profileServer
          .when(request().withMethod(method.name).withHeader(fireCloudHeader.name, fireCloudHeader.value).withPath(
            UserApiService.remoteGetKeyPath.format(uniqueId, exampleKey)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
    }
    profileServer
      .when(request().withMethod("GET").withHeader(fireCloudHeader.name, fireCloudHeader.value).withPath(UserApiService.remoteGetAllPath.format(uniqueId)))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    profileServer
      .when(request().withMethod("POST").withHeader(fireCloudHeader.name, fireCloudHeader.value).withPath(UserApiService.remoteSetKeyPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
    profileServer.stop()
    samServer.stop()
  }

  val ApiPrefix = "register/profile"

  "UserService" - {

    "when calling GET for the user registration service" - {
      "MethodNotAllowed response is not returned" in {
        Get("/register") ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          log.debug("/register: " + status)
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }

    "when calling GET for user refresh token date service" - {
      "MethodNotAllowed response is not returned" in {
        Get("/api/profile/refreshTokenDate") ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }

    "when GET-ting all profile information" - {
      "MethodNotAllowed response is not returned" in {
        Get(s"/$ApiPrefix") ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          log.debug(s"GET /$ApiPrefix: " + status)
          status shouldNot equal(MethodNotAllowed)
        }
      }
      "if anonymousGroup KVP does not exist, it gets assigned" in {
        Get("/register/profile") ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          assert(entityAs[String].parseJson.convertTo[ProfileWrapper].keyValuePairs
            .find(_.key.contains("anonymousGroup")) // .find returns Option[FireCloudKeyValue]
            .flatMap(_.value).equals(Option("new-google-group@support.something.firecloud.org")))
        }
      }
      "if anonymousGroup key exists but value is empty, a new group gets assigned, and MethodNotAllowed is not returned" in {
        Get("/register/profile") ~> dummyUserIdHeaders(userWithEmptyGoogleGroup) ~> sealRoute(userServiceRoutes) ~> check {
          assert(entityAs[String].parseJson.convertTo[ProfileWrapper].keyValuePairs
            .find(_.key.contains("anonymousGroup")) // .find returns Option[FireCloudKeyValue]
            .flatMap(_.value).equals(Option("new-google-group@support.something.firecloud.org")))
          status shouldNot equal(MethodNotAllowed)
        }
      }
      "existing anonymousGroup is not overwritten, and MethodNotAllowed is not returned" in {
        Get("/register/profile") ~> dummyUserIdHeaders(userWithGoogleGroup) ~> sealRoute(userServiceRoutes) ~> check {
          assert(entityAs[String].parseJson.convertTo[ProfileWrapper].keyValuePairs
            .find(_.key.contains("anonymousGroup")) // .find returns Option[FireCloudKeyValue]
            .flatMap(_.value).equals(Option("existing-google-group@support.something.firecloud.org")))
          status shouldNot equal(MethodNotAllowed)
        }
      }
      "a user with no contact email still gets assigned a new anonymousGroup, and MethodNotAllowed is not returned" in {
        Get("/register/profile") ~> dummyUserIdHeaders(userWithNoContactEmail) ~> sealRoute(userServiceRoutes) ~> check {
          assert(entityAs[String].parseJson.convertTo[ProfileWrapper].keyValuePairs
            .find(_.key.contains("anonymousGroup")) // .find returns Option[FireCloudKeyValue]
            .flatMap(_.value).equals(Option("new-google-group@support.something.firecloud.org")))
          status shouldNot equal(MethodNotAllowed)
        }
      }
    }

    "when POST-ting a complete profile" - {
      "OK response is returned" in {
        Post(s"/$ApiPrefix", fullProfile) ~> dummyUserIdHeaders(uniqueId) ~>
            sealRoute(registerRoutes) ~> check {
          log.debug(s"POST /$ApiPrefix: " + status)
          status should equal(OK)
        }
      }
    }

    "when POST-ting an incomplete profile" - {
      "BadRequest response is returned" in {
        val incompleteProfile = Map("name" -> randomAlpha())
        Post(s"/$ApiPrefix", incompleteProfile) ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(registerRoutes) ~> check {
          log.debug(s"POST /$ApiPrefix: " + status)
          status should equal(BadRequest)
        }
      }
    }

    "when GET-ing proxy group" - {
      "OK response is returned for valid user" in {
        Get("/api/proxyGroup/test@test.test") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }

      "NotFound response is returned for invalid user" in {
        Get("/api/proxyGroup/test@not.found") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(NotFound)
        }
      }
    }
  }

  "UserService Edge Cases" - {

    "When testing profile update for a brand new user in sam" - {
      "OK response is returned" in {
        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(NotFound.intValue)
          )
        Post(s"/$ApiPrefix", fullProfile) ~> dummyUserIdHeaders(uniqueId) ~>
            sealRoute(registerRoutes) ~> check {
          log.debug(s"POST /$ApiPrefix: " + status)
          status should equal(OK)
        }
      }
    }

    "When testing profile update for a pre-existing but non-enabled user in sam" - {
      "OK response is returned" in {
        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer.clear(request.withMethod("POST").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(NotFound.intValue)
          )
        samServer
          .when(request.withMethod("POST").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header)
              .withStatusCode(InternalServerError.intValue)
              .withBody(s"${Conflict.intValue} ${Conflict.reason}")
          )
        Post(s"/$ApiPrefix", fullProfile) ~> dummyUserIdHeaders(uniqueId) ~>
            sealRoute(registerRoutes) ~> check {
          log.debug(s"POST /$ApiPrefix: " + status)
          status should equal(OK)
        }
      }
    }

  }

  "UserService /me endpoint tests" - {

    "when calling /me without Authorization header" - {
      "Unauthorized response is returned" in {
        Get(s"/me") ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("No authorization header in request"))
          status should equal(Unauthorized)
        }
      }
    }

    "when calling /me and sam returns 401" - {
      "Unauthorized response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, "", Unauthorized.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("Request rejected by identity service"))
          status should equal(Unauthorized)
        }
      }
    }

    "when calling /me and sam returns 404" - {
      "NotFound response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, "", NotFound.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("FireCloud user registration not found"))
          status should equal(NotFound)
        }
      }
    }

    "when calling /me and sam returns 500" - {
      "InternalServerError response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, "", InternalServerError.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("Identity service encountered an unknown error"))
          status should equal(InternalServerError)
        }
      }
    }

    "when calling /me and sam says not-google-enabled" - {
      "Forbidden response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, enabledV2UserBody, OK.intValue)
        mockServerGetResponse(UserApiService.samRegisterUserDiagnosticsPath, noGoogleV2DiagnosticsBody, OK.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("FireCloud user not activated"))
          status should equal(Forbidden)
        }
      }
    }

    "when calling /me and sam says not-ldap-enabled" - {
      "Forbidden response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, noLdapV2UserBody, OK.intValue)
        mockServerGetResponse(UserApiService.samRegisterUserDiagnosticsPath, noLdapV2UserBody, OK.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("FireCloud user not activated"))
          status should equal(Forbidden)
        }
      }
    }

    "when calling /me and sam says fully enabled" - {
      "OK response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, enabledV2UserBody, OK.intValue)
        mockServerGetResponse(UserApiService.samRegisterUserDiagnosticsPath, enabledV2DiagnosticsBody, OK.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling /me and sam returns ugly json" - {
      "InternalServerError response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, uglyJsonBody, OK.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("Received unparseable response from identity service"))
          status should equal(InternalServerError)
        }
      }
    }

    "when calling /me and sam returns an unexpected HTTP response code" - {
      "echo the error code from sam" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, "", EnhanceYourCalm.intValue)

        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(EnhanceYourCalm)
        }
      }
    }

    "when calling /me?userDetailsOnly=true and sam says ldap is enabled" - {
      "OK response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, enabledV2UserBody, OK.intValue)

        Get(s"/me?userDetailsOnly=true") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling /me?userDetailsOnly=true and sam says ldap is not enabled" - {
      "Forbidden response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, noLdapV2UserBody, OK.intValue)

        Get(s"/me?userDetailsOnly=true") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("FireCloud user not activated"))
          status should equal(Forbidden)
        }
      }
    }

    "when calling /me?userDetailsOnly=true and sam returns ugly JSON" - {
      "InternalServerError response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, uglyJsonBody, OK.intValue)

        Get(s"/me?userDetailsOnly=true") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("Received unparseable response from identity service"))
          status should equal(InternalServerError)
        }
      }
    }

    "when calling /me?userDetailsOnly=false and sam says not-google-enabled" - {
      "Forbidden response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, enabledV2UserBody, OK.intValue)
        mockServerGetResponse(UserApiService.samRegisterUserDiagnosticsPath, noGoogleV2DiagnosticsBody, OK.intValue)

        Get(s"/me?userDetailsOnly=false") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("FireCloud user not activated"))
          status should equal(Forbidden)
        }
      }
    }

    "when calling /me?userDetailsOnly=false and sam says not-ldap-enabled" - {
      "Forbidden response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, noLdapV2UserBody, OK.intValue)
        mockServerGetResponse(UserApiService.samRegisterUserDiagnosticsPath, noLdapV2UserBody, OK.intValue)

        Get(s"/me?userDetailsOnly=false") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("FireCloud user not activated"))
          status should equal(Forbidden)
        }
      }
    }

    "when calling /me?userDetailsOnly=false and sam says fully enabled" - {
      "OK response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, enabledV2UserBody, OK.intValue)
        mockServerGetResponse(UserApiService.samRegisterUserDiagnosticsPath, enabledV2DiagnosticsBody, OK.intValue)

        Get(s"/me?userDetailsOnly=false") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling /me?userDetailsOnly=false and sam returns ugly json" - {
      "InternalServerError response is returned" in {
        mockServerGetResponse(UserApiService.samRegisterUserInfoPath, uglyJsonBody, OK.intValue)

        Get(s"/me?userDetailsOnly=false") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          val result = Await.result(Unmarshal(response.entity).to[String], Duration.Inf)
          assert(result.contains("Received unparseable response from identity service"))
          status should equal(InternalServerError)
        }
      }
    }

  }

  private def mockServerGetResponse(path: String, body: String, statusCode: Int): Unit = {
    samServer.clear(request.withMethod("GET").withPath(path))
    samServer
      .when(request.withMethod("GET").withPath(path))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withBody(body)
          .withHeaders(MockUtils.header).withStatusCode(statusCode)
      )
  }
}
