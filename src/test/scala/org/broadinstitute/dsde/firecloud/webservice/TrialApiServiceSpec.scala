package org.broadinstitute.dsde.firecloud.webservice

import java.time.temporal.ChronoUnit

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{HttpSamDAO, HttpThurloeDAO}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils.thurloeServerPort
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impProfileWrapper
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, ProfileWrapper, RegistrationInfo, UserInfo, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, TrialService}
import org.broadinstitute.dsde.firecloud.trial.ProjectManager
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import spray.http.HttpMethods.POST
import spray.http.StatusCodes.{Accepted, BadRequest, NoContent, OK}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.Future
import scala.util.Success

final class TrialApiServiceSpec extends BaseServiceSpec with UserApiService with TrialApiService {
  import TrialApiServiceSpec._

  // The user enrollment endpoint tested in this class lives in UserApiService,
  // but it is tested here since it is used for the trial feature.

  def actorRefFactory = system
  val localThurloeDao = new TrialApiServiceSpecThurloeDAO
  val localSamDao = new TrialApiServiceSpecSamDAO

  val trialProjectManager = system.actorOf(ProjectManager.props(app.rawlsDAO, app.trialDAO, app.googleServicesDAO), "trial-project-manager")
  val trialServiceConstructor:() => TrialService = TrialService.constructor(app.copy(thurloeDAO = localThurloeDao, samDAO = localSamDao), trialProjectManager)

  var profileServer: ClientAndServer = _

  private def profile(user:String, props:Map[String,String]): ProfileWrapper =
    ProfileWrapper(user, props.toList.map {
      case (k:String, v:String) => FireCloudKeyValue(Some(k), Some(v))
    })


  override protected def beforeAll(): Unit = {
    profileServer = startClientAndServer(thurloeServerPort)

    val allUsersAndProps = List(
      (disabledUser, disabledProps),
      (enabledUser, enabledProps),
      (enrolledUser, enrolledProps),
      (terminatedUser, terminatedProps))

    allUsersAndProps.foreach {
      case (user, props) =>
        profileServer
          .when(request()
            .withMethod("GET")
            .withHeader(fireCloudHeader.name, fireCloudHeader.value)
            .withPath(UserApiService.remoteGetAllPath.format(user)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
              .withBody(profile(user, props).toJson.compactPrint))
    }
  }

  override protected def afterAll(): Unit = {
    profileServer.stop()
  }

  "Free Trial Enrollment" - {
    val enrollPath = "/api/profile/trial"

    "User-initiated enrollment endpoint" - {
      allHttpMethodsExcept(POST) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)(enrollPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }
    }

    "attempting to enroll as a disabled user" - {
      "should be a BadRequest" in {
        Post(enrollPath) ~> dummyUserIdHeaders(disabledUser) ~> userServiceRoutes ~> check {
          status should equal(BadRequest)
        }
      }
    }

    "attempting to enroll as an enabled user" - {
      "should be NoContent success" in {
        Post(enrollPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          assertResult(NoContent, response.entity.asString) { status }
        }
      }
    }

    "attempting to enroll as an enrolled user" - {
      "should be a BadRequest" in {
        Post(enrollPath) ~> dummyUserIdHeaders(enrolledUser) ~> userServiceRoutes ~> check {
          status should equal(BadRequest)
        }
      }
    }

    "attempting to enroll as a terminated user" - {
      "should be a BadRequest" in {
        Post(enrollPath) ~> dummyUserIdHeaders(terminatedUser) ~> userServiceRoutes ~> check {
          status should equal(BadRequest)
        }
      }
    }
  }

  // TODO: Keep track of and check all users whose statuses are updated when multi-user updates are supported
  "Free Trial User Updates by Campaign Manager" - {
    val enablePath    = "/trial/manager/enable"
    val disablePath   = "/trial/manager/disable"
    val terminatePath = "/trial/manager/terminate"
    val invalidPath = "/trial/manager/unsupported-operation"

    val disabledUserEmail = Seq(disabledUser)
    val enabledUserEmail = Seq(enabledUser)

    "Manager endpoint" - {
      allHttpMethodsExcept(POST) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)(enablePath, disabledUserEmail) ~> dummyUserIdHeaders(enabledUser) ~>
            trialApiServiceRoutes ~>
            check {
              assert(!handled)
            }
        }
      }
    }

    // TODO: Test updates of users who aren't registered (i.e. no RegistrationInfo in Sam)

    // TODO: Test invalid trial status transition request (e.g. Terminated -> Enabled)

    "Attempting an invalid operation should not be handled" in {
      Post(invalidPath, disabledUserEmail) ~> dummyUserIdHeaders(disabledUser) ~> trialApiServiceRoutes ~> check {
        assert(!handled)
      }
    }

    "Attempting to enable a previously disabled user should return NoContent success" in {
      Post(enablePath, disabledUserEmail) ~> dummyUserIdHeaders(disabledUser) ~> trialApiServiceRoutes ~> check {
        assertResult(NoContent, response.entity.asString) { status }
      }
    }

    "Attempting to enable a previously enabled user should return NoContent success" in {
      Post(enablePath, enabledUserEmail) ~> dummyUserIdHeaders(enabledUser) ~> trialApiServiceRoutes ~> check {
        assertResult(NoContent, response.entity.asString) { status }
      }
    }

    // TODO: Test user disabling

    // TODO: Test user termination
  }

  "Campaign manager project-management endpoint" - {

    def projectManagementPath(operation:String, count:Option[Int] = None): String = {
      val countParam = count match {
        case Some(c) => s"&count=$c"
        case None => ""
      }
      s"/api/trial/manager/projects?operation=$operation$countParam"
    }

    allHttpMethodsExcept(POST) foreach { method =>
      s"should reject ${method.toString} method" in {
        new RequestBuilder(method)(projectManagementPath("count")) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          assert(!handled)
        }
      }
    }
    "should return BadRequest for operations other than create, verify, count, and report" in {
      Post(projectManagementPath("invalid")) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
        assertResult(BadRequest) {status}
      }
    }
    "should require a positive count for create" - {
      Seq(0,-1,-50) foreach { neg =>
        s"value tested: $neg" in {
          Post(projectManagementPath("create", Some(neg))) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
            assertResult(BadRequest) {status}
          }
        }
      }
    }
    "should return Accepted for operation 'create' with a positive count" in {
      Post(projectManagementPath("create", Some(2))) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
        assertResult(Accepted) {status}
      }
    }
    Seq("verify","count","report") foreach { op =>
      s"should return success for operation '$op'" in {
        Post(projectManagementPath(op)) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          assert(status.isSuccess)
          assertResult(OK) {status}
        }
      }
    }
  }

  final class TrialApiServiceSpecThurloeDAO extends HttpThurloeDAO {
    override def saveTrialStatus(userInfo: UserInfo, trialStatus: UserTrialStatus) = {
      // Note: because HttpThurloeDAO catches exceptions, the assertions here will
      // result in InternalServerErrors instead of appearing nicely in unit test output.
      userInfo.id match {
        case `enabledUser` =>
          val expectedExpirationDate = trialStatus.enrolledDate.plus(FireCloudConfig.Trial.durationDays, ChronoUnit.DAYS)

          assertResult(Some(TrialStates.Enrolled)) { trialStatus.state }
          assert(trialStatus.enrolledDate.toEpochMilli > 0)
          assert(trialStatus.expirationDate.toEpochMilli > 0)
          assertResult( expectedExpirationDate ) { trialStatus.expirationDate }
          assertResult(0) { trialStatus.terminatedDate.toEpochMilli }

          Future.successful(Success(()))
        case `disabledUser` => {
          assertResult(Some(TrialStates.Enabled)) { trialStatus.state }
          assert(trialStatus.enabledDate.toEpochMilli > 0)
          assert(trialStatus.enrolledDate.toEpochMilli === 0)
          assert(trialStatus.terminatedDate.toEpochMilli === 0)
          assert(trialStatus.expirationDate.toEpochMilli === 0)

          Future.successful(Success(()))
        }
        case _ => {
          fail("Should only be updating enabled and disabled users")
        }
      }
    }
  }

  final class TrialApiServiceSpecSamDAO extends HttpSamDAO {
    override def adminGetUserByEmail(email: RawlsUserEmail) = {
      Future.successful(registrationInfoByEmail(email.value))
    }
  }
}

object TrialApiServiceSpec {
  val disabledUser = "disabled-user"
  val enabledUser = "enabled-user"
  val enrolledUser = "enrolled-user"
  val terminatedUser = "terminated-user"

  val disabledProps = Map(
    "trialState" -> "Disabled",
    "trialEnabledDate" -> "555"
  )
  val enabledProps = Map(
    "trialState" -> "Enabled",
    "trialEnabledDate" -> "1"
  )
  val enrolledProps = Map(
    "trialState" -> "Enrolled",
    "trialEnabledDate" -> "11",
    "trialEnrolledDate" -> "22",
    "trialExpirationDate" -> "99"
  )
  val terminatedProps = Map(
    "trialState" -> "Terminated",
    "trialEnabledDate" -> "111",
    "trialEnrolledDate" -> "222",
    "trialTerminatedDate" -> "333",
    "trialExpirationDate" -> "999"
  )

  val workbenchEnabled = WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true)

  val enabledUserEmail = "enabled-user-email"
  val disabledUserEmail = "disabled-user-email"

  val enabledUserInfo = WorkbenchUserInfo(userSubjectId = enabledUser, enabledUserEmail)
  val disabledUserInfo = WorkbenchUserInfo(userSubjectId = disabledUser, disabledUserEmail)

  val enabledUserRegInfo = RegistrationInfo(enabledUserInfo, workbenchEnabled)
  val disabledUserRegInfo = RegistrationInfo(disabledUserInfo, workbenchEnabled)

  val registrationInfoByEmail = Map(enabledUser -> enabledUserRegInfo, disabledUser -> disabledUserRegInfo)
}
