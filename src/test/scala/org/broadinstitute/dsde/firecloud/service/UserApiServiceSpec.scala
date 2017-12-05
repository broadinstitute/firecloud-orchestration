package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.trial.ProjectManager
import org.broadinstitute.dsde.firecloud.webservice.{RegisterApiService, UserApiService}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

class UserApiServiceSpec extends BaseServiceSpec with RegisterApiService with UserApiService {

  def actorRefFactory = system

  val trialProjectManager = system.actorOf(ProjectManager.props(app.rawlsDAO, app.trialDAO, app.googleServicesDAO), "trial-project-manager")

  val registerServiceConstructor:() => RegisterService = RegisterService.constructor(app)
  val trialServiceConstructor:() => TrialService = TrialService.constructor(app, trialProjectManager)
  var workspaceServer: ClientAndServer = _
  var profileServer: ClientAndServer = _
  var samServer: ClientAndServer = _
  val httpMethods = List(HttpMethods.GET, HttpMethods.POST, HttpMethods.PUT,
    HttpMethods.DELETE, HttpMethods.PATCH, HttpMethods.OPTIONS, HttpMethods.HEAD)

  val uniqueId = "normal-user"
  val exampleKey = "favoriteColor"
  val exampleVal = "green"
  val fullProfile = BasicProfile(
    firstName= randomAlpha(),
    lastName = randomAlpha(),
    title = randomAlpha(),
    contactEmail = Option.empty,
    institute = randomAlpha(),
    institutionalProgram = randomAlpha(),
    programLocationCity = randomAlpha(),
    programLocationState = randomAlpha(),
    programLocationCountry = randomAlpha(),
    pi = randomAlpha(),
    nonProfitStatus = randomAlpha()
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

  override def beforeAll(): Unit = {

    workspaceServer = startClientAndServer(workspaceServerPort)
    workspaceServer
      .when(request.withMethod("GET").withPath(UserApiService.billingPath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    workspaceServer
      .when(request.withMethod("GET").withPath(RawlsDAO.refreshTokenDateUrl))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    workspaceServer
      .when(request.withMethod("GET").withPath(UserApiService.rawlsGroupBasePath))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    workspaceServer
      .when(request.withMethod("POST").withPath(UserApiService.rawlsGroupPath("example-group")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(Created.intValue)
      )

    workspaceServer
      .when(request.withMethod("DELETE").withPath(UserApiService.rawlsGroupPath("example-group")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    workspaceServer
      .when(request.withMethod("GET").withPath(UserApiService.rawlsGroupPath("example-group")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    workspaceServer
      .when(request.withMethod("PUT").withPath(UserApiService.rawlsGroupMemberPath("example-group", "owner", "test@test.test")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    workspaceServer
      .when(request.withMethod("DELETE").withPath(UserApiService.rawlsGroupMemberPath("example-group", "owner", "test@test.test")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )

    workspaceServer
      .when(request.withMethod("POST").withPath(UserApiService.rawlsGroupRequestAccessPath("example-group")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(NoContent.intValue)
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

    "when calling GET for user billing service" - {
      "MethodNotAllowed response is not returned" in {
        Get("/api/profile/billing") ~> dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          log.debug("/api/profile/billing: " + status)
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

    "when GET-ting my group membership" - {
      "OK response is returned" in {
        Get("/api/groups") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when POST-ing to create a group" - {
      "Created response is returned" in {
        Post("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(Created)
        }
      }
    }

    "when GET-ting membership of a group" - {
      "OK response is returned" in {
        Get("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when DELETE-ing to create a group" - {
      "OK response is returned" in {
        Delete("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when PUT-ting to add a member to a group" - {
      "OK response is returned" in {
        Put("/api/groups/example-group/owner/test@test.test") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when DELETE-ing to remove a member from a group" - {
      "OK response is returned" in {
        Delete("/api/groups/example-group/owner/test@test.test") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when POST-ing to request access to a group" - {
      "OK response is returned" in {
        Post("/api/groups/example-group/requestAccess") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(NoContent)
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
              .withBody(Conflict.intValue + " " + Conflict.reason)
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
          status should equal(Unauthorized)
        }
      }
    }

    "when calling /me and sam returns 401" - {
      "Unauthorized response is returned" in {

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(Unauthorized.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(Unauthorized)
        }
      }
    }

    "when calling /me and sam returns 404" - {
      "NotFound response is returned" in {

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(NotFound.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(NotFound)
        }
      }
    }

    "when calling /me and sam returns 500" - {
      "InternalServerError response is returned" in {

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(InternalServerError.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(InternalServerError)
        }
      }
    }

    "when calling /me and sam says not-google-enabled" - {
      "Forbidden response is returned" in {

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withBody("""{"enabled": {"google": false, "ldap": true, "allUsersGroup": true}, "userInfo": {"userSubjectId": "1111111111", "userEmail": "no@nope.org"}}""")
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(Forbidden)
        }
      }
    }

    "when calling /me and sam says not-ldap-enabled" - {
      "Forbidden response is returned" in {

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withBody("""{"enabled": {"google": true, "ldap": false, "allUsersGroup": true}, "userInfo": {"userSubjectId": "1111111111", "userEmail": "no@nope.org"}}""")
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(Forbidden)
        }
      }
    }

    "when calling /me and sam says fully enabled" - {
      "OK response is returned" in {

        println(UserApiService.samRegisterUserPath)

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withBody("""{"enabled": {"google": true, "ldap": true, "allUsersGroup": true}, "userInfo": {"userSubjectId": "1111111111", "userEmail": "no@nope.org"}}""")
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling /me and sam returns ugly json" - {
      "InternalServerError response is returned" in {

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withBody("""{"userInfo": "whaaaaaaat??"}""")
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(InternalServerError)
        }
      }
    }

    "when calling /me and sam returns an unexpected HTTP response code" - {
      "echo the error code from sam" in {

        samServer.clear(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
        samServer
          .when(request.withMethod("GET").withPath(UserApiService.samRegisterUserPath))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(EnhanceYourCalm.intValue)
          )
        Get(s"/me") ~> dummyAuthHeaders ~> sealRoute(userServiceRoutes) ~> check {
          status should equal(EnhanceYourCalm)
        }
      }
    }


  }

}
