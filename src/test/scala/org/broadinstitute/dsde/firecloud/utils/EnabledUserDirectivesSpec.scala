package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{ImATeapot, OK}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route.seal
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.firecloud.FireCloudApiService
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.ErrorReportFormat
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.Header
import org.mockserver.model.HttpRequest.request
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class EnabledUserDirectivesSpec
  extends AnyFreeSpec
    with EnabledUserDirectives
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterAll
    with SprayJsonSupport {

  val enabledUser: UserInfo = UserInfo("enabled@nowhere.com", OAuth2BearerToken("enabled"), 123456, "enabled-id")
  val disabledUser: UserInfo = UserInfo("disabled@nowhere.com", OAuth2BearerToken("disabled"), 123456, "disabled-id")
  val samApiExceptionUser: UserInfo = UserInfo("samapiexception@nowhere.com", OAuth2BearerToken("samapiexception"), 123456, "samapiexception-id")

  val samUserInfoPath = "/register/user/v2/self/info"

  var mockSamServer: ClientAndServer = _

  def stopMockSamServer(): Unit = {
    mockSamServer.stop()
  }

  def startMockSamServer(): Unit = {
    mockSamServer = startClientAndServer(MockUtils.samServerPort)

    // enabled user
    mockSamServer
      .when(request
        .withMethod("GET")
        .withPath(samUserInfoPath)
        .withHeader(new Header("Authorization", "Bearer enabled")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody("""{
                                                    |  "adminEnabled": true,
                                                    |  "enabled": true,
                                                    |  "userEmail": "enabled@nowhere.com",
                                                    |  "userSubjectId": "enabled-id"
                                                    |}""".stripMargin).withStatusCode(OK.intValue)
      )

    // disabled user
    mockSamServer
      .when(request
        .withMethod("GET")
        .withPath(samUserInfoPath)
        .withHeader(new Header("Authorization", "Bearer disabled")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody("""{
                                                    |  "adminEnabled": false,
                                                    |  "enabled": false,
                                                    |  "userEmail": "disabled@nowhere.com",
                                                    |  "userSubjectId": "disabled-id"
                                                    |}""".stripMargin).withStatusCode(OK.intValue)
      )

    // ApiException from the Sam client
    mockSamServer
      .when(request
        .withMethod("GET")
        .withPath(samUserInfoPath)
        .withHeader(new Header("Authorization", "Bearer samapiexception")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody("""{
                                                    |  "source": "Sam",
                                                    |  "message": "unit test error",
                                                    |  "statusCode": 418,
                                                    |  "causes": [],
                                                    |}""".stripMargin).withStatusCode(ImATeapot.intValue)
      )

  }

  override def beforeAll(): Unit = startMockSamServer()
  override def afterAll(): Unit = stopMockSamServer()

  // make sure to bring the exception handler into scope. This is what translates
  // the exceptions into http responses, and it's used by FireCloudApiService, so
  // we also use it here.
  implicit val exceptionHandler: ExceptionHandler = FireCloudApiService.exceptionHandler

  // define a simple route that uses requireEnabledUser
  def userEnabledRoute(userInfo: UserInfo): Route = seal({
    get {
      requireEnabledUser(userInfo, s"http://localhost:${MockUtils.samServerPort}") {
        complete("route was successful")
      }
    }
  })

  "requireEnabledUser" - {
    "should allow enabled users" in {
      Get() ~> userEnabledRoute(enabledUser) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "route was successful"
      }
    }
    "should 403 for disabled users" in {
      Get() ~> userEnabledRoute(disabledUser) ~> check {
        status shouldBe StatusCodes.Forbidden
        val err = responseAs[ErrorReport]
        err.message shouldBe StatusCodes.Forbidden.defaultMessage
      }
    }
    "should bubble up exceptions encountered while calling Sam" in {
      Get() ~> userEnabledRoute(samApiExceptionUser) ~> check {
        status shouldBe StatusCodes.ImATeapot
        val err = responseAs[ErrorReport]
        err.message shouldBe s"Client Error (${StatusCodes.ImATeapot.intValue})"
      }
    }
  }
}
