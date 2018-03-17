package org.broadinstitute.dsde.firecloud.webservice

import org.broadinstitute.dsde.firecloud.{FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{MockSamDAO, MockThurloeDAO}
import org.broadinstitute.dsde.firecloud.dataaccess.MockThurloeDAO._
import org.broadinstitute.dsde.firecloud.mock.MockUtils.randomAlpha
import org.broadinstitute.dsde.firecloud.model.ErrorReportExtensions.FCErrorReport
import org.broadinstitute.dsde.firecloud.model.{BasicProfile, RegistrationInfo, Trial, UserInfo, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impBasicProfile, impRegistrationInfo}
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates._
import org.broadinstitute.dsde.firecloud.model.Trial.UserTrialStatus
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, FireCloudRequestBuilding, RegisterService}
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import spray.http.HttpResponse
import spray.http.StatusCodes.{NotFound, OK}
import spray.httpx.SprayJsonSupport
import spray.json.DefaultJsonProtocol

import scala.concurrent.Future
import scala.util.Try

class TrialRegistrationSpec extends BaseServiceSpec with RegisterApiService with FireCloudRequestBuilding
  with SprayJsonSupport with DefaultJsonProtocol {

  def actorRefFactory = system

  val regSamDao = new UserRegistrationSamDAO()
  val regThurloeDao = new UserRegistrationThurloeDAO()

  val testApp = app.copy(samDAO = regSamDao, thurloeDAO = regThurloeDao)

  val registerServiceConstructor: () => RegisterService = RegisterService.constructor(testApp)

  val registerPath = "/register/profile"

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

  "When updating the profile for a pre-existing, registered user" - {
    "should not read or write trial status" in {
      Post(registerPath, fullProfile) ~> dummyUserIdHeaders(NORMAL_USER, email = NORMAL_USER) ~> sealRoute(registerRoutes) ~> check {
        status should equal(OK)
        val regInfo = responseAs[RegistrationInfo]
        // if NORMAL_USER triggers a call to either getTrialStatus or saveTrialStatus, UserRegistrationThurloeDAO will
        // fail and RegistrationInfo will have a message below.
        assert(regInfo.messages.isEmpty, "- if this reports a BoxedError it is likely the assert inside UserRegistrationThurloeDAO.getTrialStatus or saveTrialStatus")
      }
    }
  }

  "When registering a brand new user in sam" - {
    "should enable for free credits" in {
      Post(registerPath, fullProfile) ~> dummyUserIdHeaders(TRIAL_SELF_ENABLED, email = TRIAL_SELF_ENABLED) ~> sealRoute(registerRoutes) ~> check {
        // triggers an assert in UserRegistrationThurloeDAO.saveTrialStatus
        status should equal(OK)
        val regInfo = responseAs[RegistrationInfo]
        assert(regInfo.messages.isEmpty, "- if this reports a BoxedError it is likely the assert inside UserRegistrationThurloeDAO.saveTrialStatus")
      }
    }
  }

  "When registering a pre-existing but non-enabled user in sam" - {
    "should enable for free credits" in {
      Post(registerPath, fullProfile) ~> dummyUserIdHeaders(TRIAL_SELF_ENABLED_PREEXISTING, email = TRIAL_SELF_ENABLED_PREEXISTING) ~> sealRoute(registerRoutes) ~> check {
        // triggers an assert in UserRegistrationThurloeDAO.saveTrialStatus
        status should equal(OK)
        val regInfo = responseAs[RegistrationInfo]
        assert(regInfo.messages.isEmpty, "- if this reports a BoxedError it is likely the assert inside UserRegistrationThurloeDAO.saveTrialStatus")
      }

    }
  }

  "When registering a brand new user encounters an error saving Enabled trial status for free credits" - {
    "should complete registration with a message in responses" in {
      Post(registerPath, fullProfile) ~> dummyUserIdHeaders(TRIAL_SELF_ENABLED_ERROR, email = TRIAL_SELF_ENABLED_ERROR) ~> sealRoute(registerRoutes) ~> check {
        status should equal(OK)
        val regInfo = responseAs[RegistrationInfo]
        assert(regInfo.messages.contains(List("Error enabling free credits during registration. Underlying error: Unit test saveTrialStatus intentional exception")))
      }
    }
  }

}

class UserRegistrationThurloeDAO extends MockThurloeDAO {

  import scala.concurrent.ExecutionContext.Implicits.global
  import MockThurloeDAO._

  override def getTrialStatus(forUserId: String, callerToken: WithAccessToken): Future[Trial.UserTrialStatus] = {
    forUserId match {
      case `TRIAL_SELF_ENABLED_ERROR` | `TRIAL_SELF_ENABLED` | `TRIAL_SELF_ENABLED_PREEXISTING` =>
        Future.successful(UserTrialStatus(forUserId, None, userAgreed = true, 0, 0, 0, 0, None))
      case _ =>
        Future.failed(new FireCloudException(s"User $forUserId should not be calling getTrialStatus"))
    }
  }


  override def saveTrialStatus(forUserId: String, callerToken: WithAccessToken, trialStatus: Trial.UserTrialStatus): Future[Try[Unit]] = {
    forUserId match {
      case `TRIAL_SELF_ENABLED_ERROR` =>
        Future.failed(new FireCloudException(s"Unit test saveTrialStatus intentional exception"))
      case `TRIAL_SELF_ENABLED` | `TRIAL_SELF_ENABLED_PREEXISTING` =>
        // when saving trial status for a self-registered user, new or pre-existing, the new status should have state=Enabled
        assert(trialStatus.state.contains(Enabled))
        super.saveTrialStatus(forUserId, callerToken, trialStatus)
      case _ =>
        Future.failed(new FireCloudException(s"User $forUserId should not be calling saveTrialStatus"))
    }
  }

}

class UserRegistrationSamDAO extends MockSamDAO {
  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    userInfo match {
      case x:UserInfo if List(TRIAL_SELF_ENABLED, TRIAL_SELF_ENABLED_ERROR).contains(x.id) =>
        Future.failed(new FireCloudExceptionWithErrorReport(FCErrorReport(HttpResponse(status = NotFound))))
      case y:UserInfo if List(TRIAL_SELF_ENABLED_PREEXISTING).contains(y.id) =>
        Future.successful(RegistrationInfo(
          WorkbenchUserInfo(userSubjectId = y.id, userEmail = y.userEmail),
          WorkbenchEnabled(google = false, ldap = false, allUsersGroup = false)))
      case _ =>
        super.getRegistrationStatus(userInfo)
    }
  }
}
