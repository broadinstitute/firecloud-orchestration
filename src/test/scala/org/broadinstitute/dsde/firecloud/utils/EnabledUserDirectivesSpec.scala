package org.broadinstitute.dsde.firecloud.utils

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route.seal
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.firecloud.{FireCloudApiService, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.SamDAO
import org.broadinstitute.dsde.firecloud.model.UserInfo
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.workbench.client.sam.ApiException
import org.broadinstitute.dsde.workbench.client.sam.model.UserStatusInfo
import org.mockito.Mockito.{when, RETURNS_SMART_NULLS}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{ExecutionContext, Future}

class EnabledUserDirectivesSpec
    extends AnyFreeSpec
    with EnabledUserDirectives
    with Matchers
    with ScalatestRouteTest
    with SprayJsonSupport
    with MockitoSugar {

  implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val samDao: SamDAO = mock[SamDAO](RETURNS_SMART_NULLS)
  val enabledUser: UserInfo = UserInfo("enabled@nowhere.com", OAuth2BearerToken("enabled"), 123456, "enabled-id")
  val disabledUser: UserInfo = UserInfo("disabled@nowhere.com", OAuth2BearerToken("disabled"), 123456, "disabled-id")
  val unregisteredUser: UserInfo =
    UserInfo("unregistered@nowhere.com", OAuth2BearerToken("unregistered"), 123456, "unregistered-id")
  val samApiExceptionUser: UserInfo =
    UserInfo("samapiexception@nowhere.com", OAuth2BearerToken("samapiexception"), 123456, "samapiexception-id")

  // make sure to bring the exception handler into scope. This is what translates
  // the exceptions into http responses, and it's used by FireCloudApiService, so
  // we also use it here.
  implicit val exceptionHandler: ExceptionHandler = FireCloudApiService.exceptionHandler

  // define a simple route that uses requireEnabledUser
  def userEnabledRoute(userInfo: UserInfo): Route = seal {
    get {
      requireEnabledUser(userInfo) {
        complete("route was successful")
      }
    }
  }

  "requireEnabledUser" - {
    "should allow enabled users" in {
      when(samDao.getUserStatus(enabledUser)).thenReturn(Future.successful(new UserStatusInfo().enabled(true)))
      Get() ~> userEnabledRoute(enabledUser) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "route was successful"
      }
    }
    "should 401 for disabled users" in {
      when(samDao.getUserStatus(disabledUser)).thenReturn(Future.successful(new UserStatusInfo().enabled(false)))
      Get() ~> userEnabledRoute(disabledUser) ~> check {
        status shouldBe StatusCodes.Unauthorized
        val err = responseAs[ErrorReport]
        err.message shouldBe "User is disabled."
      }
    }
    "should 401 for unregistered users" in {
      when(samDao.getUserStatus(disabledUser)).thenReturn(
        Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "user not found")))
      )
      Get() ~> userEnabledRoute(disabledUser) ~> check {
        println(responseAs[String])
        status shouldBe StatusCodes.Unauthorized
        val err = responseAs[ErrorReport]
        err.message shouldBe "User is not registered."
      }
    }
    "should bubble up exceptions encountered while calling Sam" in {
      when(samDao.getUserStatus(samApiExceptionUser))
        .thenReturn(Future.failed(new ApiException(StatusCodes.ImATeapot.intValue, "other exception")))
      Get() ~> userEnabledRoute(samApiExceptionUser) ~> check {
        status shouldBe StatusCodes.ImATeapot
      }
    }
  }
}
