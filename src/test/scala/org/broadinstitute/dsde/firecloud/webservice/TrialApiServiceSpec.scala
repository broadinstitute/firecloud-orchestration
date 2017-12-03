package org.broadinstitute.dsde.firecloud.webservice

import java.time.temporal.ChronoUnit

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{HttpSamDAO, HttpThurloeDAO}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils.{samServerPort, thurloeServerPort}
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, ProfileWrapper, RegistrationInfo, UserInfo, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impProfileWrapper
import org.broadinstitute.dsde.firecloud.model.Trial.{TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, TrialService}
import org.broadinstitute.dsde.rawls.model.RawlsUserEmail
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import spray.http.StatusCodes.{BadRequest, NoContent, NotFound, OK}
import spray.http.HttpMethods.POST
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.Future

final class TrialApiServiceSpec extends BaseServiceSpec with UserApiService with TrialApiService {
  import TrialApiServiceSpec._

  // The user enrollment endpoint tested in this class lives in UserApiService,
  // but it is tested here since it is used for the trial feature.

  def actorRefFactory = system
  val localThurloeDao = new TrialApiServiceSpecThurloeDAO
  val localSamDao = new TrialApiServiceSpecSamDAO
  val trialServiceConstructor:() => TrialService =
    TrialService.constructor(app.copy(thurloeDAO = localThurloeDao, samDAO = localSamDao))

  var profileServer: ClientAndServer = _
  var samServer: ClientAndServer = _

  private def profile(user:String, props:Map[String,String]): ProfileWrapper =
    ProfileWrapper(user, props.toList.map {
      case (k:String, v:String) => FireCloudKeyValue(Some(k), Some(v))
    })


  override protected def beforeAll(): Unit = {
    profileServer = startClientAndServer(thurloeServerPort)
    samServer = startClientAndServer(samServerPort)

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
  "Free Trial Manager Updates" - {
    val enablePath    = "/trial/manager/enable"
    val disablePath   = "/trial/manager/disable"
    val terminatePath = "/trial/manager/terminate"
    val invalidPath = "/trial/manager/unsupported-operation"

    val userEmails = Seq(disabledUser)

    "Manager endpoint" - {
      allHttpMethodsExcept(POST) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)(enablePath, userEmails) ~> dummyUserIdHeaders(enabledUser) ~>
            trialApiServiceRoutes ~>
            check {
              assert(!handled)
            }
        }
      }

      "attempting an invalid operation" - {
        "should not be handled" in {
          Post(invalidPath, userEmails) ~> dummyUserIdHeaders(disabledUser) ~> trialApiServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }

      "attempting to enable a disabled user" - {
        "should be NoContent success" in {
          Post(enablePath, userEmails) ~> dummyUserIdHeaders(disabledUser) ~> trialApiServiceRoutes ~> check {
            assertResult(NoContent, response.entity.asString) { status }
          }
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

          assertResult(Some(TrialStates.Enrolled)) { trialStatus.currentState }
          assert(trialStatus.enrolledDate.toEpochMilli > 0)
          assert(trialStatus.expirationDate.toEpochMilli > 0)
          assertResult( expectedExpirationDate ) { trialStatus.expirationDate }
          assertResult(0) { trialStatus.terminatedDate.toEpochMilli }

          Future.successful(())
        case `disabledUser` => {
          assertResult(Some(TrialStates.Enabled)) { trialStatus.currentState }
          assert(trialStatus.enabledDate.toEpochMilli > 0)
          assert(trialStatus.enrolledDate.toEpochMilli === 0)
          assert(trialStatus.terminatedDate.toEpochMilli === 0)
          assert(trialStatus.expirationDate.toEpochMilli === 0)

          Future.successful(())
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

  val disabledProps = Map.empty[String,String]
  val enabledProps = Map(
    "trialCurrentState" -> "Enabled",
    "trialEnabledDate" -> "1"
  )
  val enrolledProps = Map(
    "trialCurrentState" -> "Enrolled",
    "trialEnabledDate" -> "11",
    "trialEnrolledDate" -> "22",
    "trialExpirationDate" -> "99"
  )
  val terminatedProps = Map(
    "trialCurrentState" -> "Terminated",
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
