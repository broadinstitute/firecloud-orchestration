package org.broadinstitute.dsde.firecloud.webservice

import java.time.temporal.ChronoUnit

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{HttpSamDAO, HttpThurloeDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils.thurloeServerPort
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impProfileWrapper
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled, Enrolled}
import org.broadinstitute.dsde.firecloud.model.Trial.{CreationStatuses, TrialProject, TrialStates, UserTrialStatus}
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, ProfileWrapper, RegistrationInfo, UserInfo, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, TrialService}
import org.broadinstitute.dsde.firecloud.trial.ProjectManager
import org.broadinstitute.dsde.firecloud.trial.ProjectManagerSpec.ProjectManagerSpecTrialDAO
import org.broadinstitute.dsde.rawls.model.{RawlsBillingProjectName, RawlsUserEmail}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import spray.http.HttpMethods.{POST, PUT}
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.Future
import scala.util.{Failure, Success}

final class TrialApiServiceSpec extends BaseServiceSpec with UserApiService with TrialApiService {
  import TrialApiServiceSpec._

  // The user enrollment endpoint tested in this class lives in UserApiService,
  // but it is tested here since it is used for the trial feature.

  def actorRefFactory = system
  val localThurloeDao = new TrialApiServiceSpecThurloeDAO
  val localSamDao = new TrialApiServiceSpecSamDAO
  val localTrialDao = new TrialApiServiceSpecTrialDAO
  val localRawlsDao = new TrialApiServiceSpecRawlsDAO

  val trialProjectManager = system.actorOf(ProjectManager.props(app.rawlsDAO, app.trialDAO, app.googleServicesDAO), "trial-project-manager")
  val trialServiceConstructor:() => TrialService =
    TrialService.constructor(
      app.copy(
        thurloeDAO = localThurloeDao,
        samDAO = localSamDao,
        trialDAO = localTrialDao,
        rawlsDAO = localRawlsDao),
      trialProjectManager)

  var localThurloeServer: ClientAndServer = _

  private def profile(user:String, props:Map[String,String]): ProfileWrapper =
    ProfileWrapper(user, props.toList.map {
      case (k:String, v:String) => FireCloudKeyValue(Some(k), Some(v))
    })


  override protected def beforeAll(): Unit = {
    // Used by positive and negative tests where `getTrialStatus` is called
    localThurloeServer = startClientAndServer(thurloeServerPort)

    val allUsersAndProps = List(
      (dummy2User, dummy2Props),
      (disabledUser, disabledProps),
      (enabledUser, enabledProps),
      (enabledButNotAgreedUser, enabledButNotAgreedProps),
      (enrolledUser, enrolledProps),
      (terminatedUser, terminatedProps))

    allUsersAndProps.foreach {
      case (user, props) =>
        localThurloeServer
          .when(request()
            .withMethod("GET")
            .withHeader(fireCloudHeader.name, fireCloudHeader.value)
            .withPath(UserApiService.remoteGetAllPath.format(user)))
          .respond(
            org.mockserver.model.HttpResponse.response()
              .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
              .withBody(profile(user, props).toJson.compactPrint))
    }

    // For testing error scenarios
    localThurloeServer
      .when(request()
        .withMethod("GET")
        .withHeader(fireCloudHeader.name, fireCloudHeader.value)
        .withPath(UserApiService.remoteGetAllPath.format(dummy1User)))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(InternalServerError.intValue))
  }

  override protected def afterAll(): Unit = {
    localThurloeServer.stop()
  }

  "User-initiated User Agreement endpoint" - {
    val userAgreementPath = "/api/profile/trial/userAgreement"
    val allButEnabledUsers = Seq(disabledUser, enrolledUser, terminatedUser)

    "Failing due to Thurloe error should return a server error to the user" in {
      Put(userAgreementPath) ~> dummyUserIdHeaders(dummy1User) ~> userServiceRoutes ~> check {
        status should equal(InternalServerError)
      }
    }

    allHttpMethodsExcept(PUT) foreach { method =>
      s"should reject ${method.toString} method" in {
        new RequestBuilder(method)(userAgreementPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          assert(!handled)
        }
      }
    }

    allButEnabledUsers foreach { user =>
      s"$user should not be able to agree to terms" in {
        Put(userAgreementPath) ~> dummyUserIdHeaders(user) ~> userServiceRoutes ~> check {
          status should equal(Forbidden)
        }
      }
    }

    "attempting to agree to terms as enabled user" in {
      Put(userAgreementPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
        status should equal(NoContent)
      }
    }
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

    "attempting to enroll as an enabled user that agreed to terms" - {
      "should be NoContent success" in {
        Post(enrollPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          assertResult(NoContent, response.entity.asString) { status }
        }
      }
    }

    "enrolling as an enabled user that DID NOT agree to terms" - {
      "should be Forbidden" in {
        Post(enrollPath) ~> dummyUserIdHeaders(enabledButNotAgreedUser) ~> userServiceRoutes ~> check {
          assertResult(Forbidden, response.entity.asString) { status }
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
  "Campaign manager user enable/disable/terminate endpoint" - {
    val enablePath    = "/trial/manager/enable"
    val disablePath   = "/trial/manager/disable"
    val terminatePath = "/trial/manager/terminate"
    val invalidPath = "/trial/manager/unsupported-operation"

    val dummy1UserEmails = Seq(dummy1User)
    val dummy2UserEmails = Seq(dummy2User)
    val disabledUserEmails = Seq(disabledUser)
    val enabledUserEmails = Seq(enabledUser)
    val enabledButNotAgreedUserEmails = Seq(enabledButNotAgreedUser)
    val enrolledUserEmails = Seq(enrolledUser)
    val terminatedUserEmails = Seq(terminatedUser)

    "Manager endpoint" - {
      allHttpMethodsExcept(POST) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)(enablePath, disabledUserEmails) ~> dummyUserIdHeaders(enabledUser) ~>
            trialApiServiceRoutes ~>
            check {
              assert(!handled)
            }
        }
      }
    }

    // TODO: Test updates of users who aren't registered (i.e. no RegistrationInfo in Sam)

    "Thurloe errors" - {
      "preventing us from getting user trial status should return a server error to the user" in {
        // Utilizes mockThurloeServer
        Post(enablePath, dummy1UserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assertResult(InternalServerError, response.entity.asString) { status }
        }
      }

      "preventing us from saving user trial status should be properly communicated to the user" in {
        // Utilizes localThurloeDao
        Post(disablePath, dummy2UserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assertResult(InternalServerError, response.entity.asString) { status }
        }
      }
    }

    "Attempting an invalid operation should not be handled" in {
      Post(invalidPath, disabledUserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        assert(!handled)
      }
    }

    "Attempting to enable a previously disabled user should return success" in {
      Post(enablePath, Seq(disabledUser, enabledUser)) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        val enableResponse = responseAs[Map[String, Seq[String]]]
        assertResult(Map("Success"->Seq(disabledUser), "NoChangeRequired"->Seq(enabledUser))) { enableResponse }
        assertResult(OK) {status}
      }
    }

    "Attempting to enable a previously enabled user should return NoChangeRequired success" in {
      Post(enablePath, Seq(enabledUser)) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        val enableResponse = responseAs[Map[String, Seq[String]]]
        assertResult(Map("NoChangeRequired"->Seq(enabledUser))) { enableResponse }
        assertResult(OK) {status}
      }
    }

    // Positive and negative tests for status transitions among {enable, disable, terminate}
    val allManagerOperations = Seq(enablePath, disablePath, terminatePath)

    // Define the operations that should succeed. All other operations should fail.
    val successes: Map[Seq[String], Seq[String]] = Map(
      disabledUserEmails -> Seq(enablePath, disablePath),
      enabledUserEmails -> Seq(enablePath, disablePath),
      enrolledUserEmails -> Seq(terminatePath),
      terminatedUserEmails -> Seq(terminatePath)
    )

    successes.foreach { case (targetUsers: Seq[String], successfulOperationPaths: Seq[String]) =>
      successfulOperationPaths.foreach { successfulOperationPath =>
        s"Attempting $successfulOperationPath on ${targetUsers.head} as $manager should return NoContent success" in {
          Post(successfulOperationPath, targetUsers) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
            assertResult(OK, response.entity.asString) { status }
          }
        }

        s"Attempting $successfulOperationPath on ${targetUsers.head} as $unauthorizedUser should return Forbidden" in {
          Post(successfulOperationPath, targetUsers) ~> dummyUserIdHeaders(unauthorizedUser) ~> trialApiServiceRoutes ~> check {
            assertResult(Forbidden, response.entity.asString) { status }
          }
        }
      }
      val expectedFailPaths: Set[String] = allManagerOperations.toSet diff successfulOperationPaths.toSet
      expectedFailPaths.foreach { path =>
        s"Attempting $path on ${targetUsers.head} should return InternalServerError failure" in {
          Post(path, targetUsers) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
            assertResult(InternalServerError, response.entity.asString) {
              status
            }
          }
        }
      }
    }
  }

  "Campaign manager project-management endpoint" - {

    def projectManagementPath(operation:String, count:Option[Int] = None): String = {
      val countParam = count match {
        case Some(c) => s"&count=$c"
        case None => ""
      }
      s"/trial/manager/projects?operation=$operation$countParam"
    }

    allHttpMethodsExcept(POST) foreach { method =>
      s"should reject ${method.toString} method" in {
        new RequestBuilder(method)(projectManagementPath("count")) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assert(!handled)
        }
      }
    }
    "should return BadRequest for operations other than create, verify, count, and report" in {
      Post(projectManagementPath("invalid")) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        assertResult(BadRequest) {status}
      }
    }
    "should require a positive count for create" - {
      Seq(0,-1,-50) foreach { neg =>
        s"value tested: $neg" in {
          Post(projectManagementPath("create", Some(neg))) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
            assertResult(BadRequest) {status}
          }
        }
      }
    }
    "should return Accepted for operation 'create' with a positive count" in {
      Post(projectManagementPath("create", Some(2))) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        assertResult(Accepted) {status}
      }
    }
    Seq("verify","count","report") foreach { op =>
      s"should return success for operation '$op'" in {
        Post(projectManagementPath(op)) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assert(status.isSuccess)
          assertResult(OK) {status}
        }
      }
    }
  }

  "Campaign manager report endpoints" - {

    "Create Report endpoint" - {

      allHttpMethodsExcept(POST) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)("/trial/manager/report") ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }

      "should succeed with a create request" in {
        Post("/trial/manager/report") ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assert(status.isSuccess)
          assertResult(OK) {status}
        }
      }

      "non-campaign manager should fail creating request" in {
        Post("/trial/manager/report") ~> dummyUserIdHeaders(unauthorizedUser) ~> trialApiServiceRoutes ~> check {
          assert(status.isFailure)
          assertResult(Forbidden) {status}
        }
      }

    }

    "Update Report endpoint" - {

      allHttpMethodsExcept(PUT) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)("/trial/manager/report/12345678") ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }

      "should succeed with an update request" in {
        Put("/trial/manager/report/12345") ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assert(status.isSuccess)
          assertResult(OK) {status}
        }
      }

      "non-campaign manager should fail an update request" in {
        Put("/trial/manager/report/12345") ~> dummyUserIdHeaders(unauthorizedUser) ~> trialApiServiceRoutes ~> check {
          assert(status.isFailure)
          assertResult(Forbidden) {status}
        }
      }

    }

  }

  class TrialApiServiceSpecTrialDAO extends ProjectManagerSpecTrialDAO {
    override def claimProjectRecord(userInfo: WorkbenchUserInfo): TrialProject = {
      TrialProject(RawlsBillingProjectName(userInfo.userEmail), true, Some(userInfo), Some(CreationStatuses.Ready))
    }

    override def releaseProjectRecord(projectName: RawlsBillingProjectName): TrialProject = {
      println("releasing the project " + projectName.value)
      TrialProject(projectName)
    }
  }

  /** Used by positive and negative tests where `saveTrialStatus` is called */
  final class TrialApiServiceSpecThurloeDAO extends HttpThurloeDAO {
    override def saveTrialStatus(forUserId: String, callerToken: WithAccessToken, trialStatus: UserTrialStatus) = {
      // Note: because HttpThurloeDAO catches exceptions, the assertions here will
      // result in InternalServerErrors instead of appearing nicely in unit test output.
      forUserId match {
        case `enabledUser` => trialStatus.state match {
          case Some(Enrolled) =>
            val expectedExpirationDate = trialStatus.enrolledDate.plus(FireCloudConfig.Trial.durationDays, ChronoUnit.DAYS)

            assert(trialStatus.enrolledDate.toEpochMilli > 0)
            assert(trialStatus.expirationDate.toEpochMilli > 0)
            assertResult( expectedExpirationDate ) { trialStatus.expirationDate }
            assertResult(0) { trialStatus.terminatedDate.toEpochMilli }

            Future.successful(Success(()))
          case Some(Enabled) =>
            Future.successful(Success(()))
          case Some(Disabled) =>
            Future.successful(Success(()))
          case _ =>
            Future.failed(new IllegalArgumentException(s"Enabled status can only transition to Enrolled or Disabled"))
        }
        case `disabledUser` => {
          assertResult(Some(TrialStates.Enabled)) {
            trialStatus.state
          }
          assert(trialStatus.enabledDate.toEpochMilli > 0)
          assert(trialStatus.enrolledDate.toEpochMilli === 0)
          assert(trialStatus.terminatedDate.toEpochMilli === 0)
          assert(trialStatus.expirationDate.toEpochMilli === 0)

          Future.successful(Success(()))
        }
        case `enrolledUser` => {
          assertResult(Some(TrialStates.Terminated)) { trialStatus.state }
          assert(trialStatus.enabledDate.toEpochMilli > 0)
          assert(trialStatus.enrolledDate.toEpochMilli > 0)
          assert(trialStatus.terminatedDate.toEpochMilli > 0)
          assert(trialStatus.expirationDate.toEpochMilli > 0)

          Future.successful(Success(()))
        }
        case user @ `dummy2User` => { // Mocking Thurloe status saving failures
          Future.failed(new InternalError(s"Cannot save trial status for $user"))
        }
        case _ => {
          fail("Should only be updating enabled, disabled or enrolled users")
        }
      }
    }
  }

  final class TrialApiServiceSpecSamDAO extends HttpSamDAO {
    override def adminGetUserByEmail(email: RawlsUserEmail) = {
      Future.successful(registrationInfoByEmail(email.value))
    }
  }

  /** Used to ensure that manager endpoints only serve managers */
  final class TrialApiServiceSpecRawlsDAO extends MockRawlsDAO {
    private val groupMap = Map(
      "apples" -> Seq("alice"),
      "bananas" -> Seq("bob"),
      "trial_managers" -> Seq(manager) // the name "trial_managers" is defined in reference.conf
    )

    override def isGroupMember(userInfo: UserInfo, groupName: String): Future[Boolean] = {
      userInfo.id match {
        case "failme" => Future.failed(new Exception("intentional exception for unit tests"))
        case TrialApiServiceSpec.unauthorizedUser => Future.successful(false)
        case _ => Future.successful(groupMap.getOrElse(groupName, Seq.empty[String]).contains(userInfo.id))
      }
    }
  }
}

object TrialApiServiceSpec {
  val manager = "manager"
  val unauthorizedUser = "unauthorized"
  val dummy1User = "dummy1-user"
  val dummy2User = "dummy2-user"
  val disabledUser = "disabled-user"
  val disabledUser2 = "disabled-user2"
  val enabledUser = "enabled-user"
  val enabledButNotAgreedUser = "enabled-but-not-agreed-user"
  val enrolledUser = "enrolled-user"
  val terminatedUser = "terminated-user"

  val dummy1Props = Map(
    "trialState" -> "Enabled"
  )

  val dummy2Props = Map(
    "trialState" -> "Enabled"
  )
  val disabledProps = Map(
    "trialState" -> "Disabled",
    "trialEnabledDate" -> "555"
  )
  val enabledProps = Map(
    "trialState" -> "Enabled",
    "userAgreed" -> "true",
    "trialEnabledDate" -> "1",
    "trialBillingProjectName" -> "testproject"
  )
  val enabledButNotAgreedProps = Map(
    "trialState" -> "Enabled",
    "userAgreed" -> "false",
    "trialEnabledDate" -> "1",
    "trialBillingProjectName" -> "testproject"
  )
  val enrolledProps = Map(
    "trialState" -> "Enrolled",
    "trialEnabledDate" -> "11",
    "trialEnrolledDate" -> "22",
    "trialExpirationDate" -> "99",
    "trialBillingProjectName" -> "testproject"
  )
  val terminatedProps = Map(
    "trialState" -> "Terminated",
    "trialEnabledDate" -> "111",
    "trialEnrolledDate" -> "222",
    "trialTerminatedDate" -> "333",
    "trialExpirationDate" -> "999"
  )

  val workbenchEnabled = WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true)

  val dummy1UserEmail = "dummy1-user-email"
  val dummy2UserEmail = "dummy2-user-email"
  val enabledUserEmail = "enabled-user-email"
  val disabledUserEmail = "disabled-user-email"
  val enrolledUserEmail = "enrolled-user-email"
  val terminatedUserEmail = "terminated-user-email"

  val dummy1UserInfo = WorkbenchUserInfo(userSubjectId = dummy1User, dummy1UserEmail)
  val dummy2UserInfo = WorkbenchUserInfo(userSubjectId = dummy2User, dummy2UserEmail)
  val enabledUserInfo = WorkbenchUserInfo(userSubjectId = enabledUser, enabledUserEmail)
  val disabledUserInfo = WorkbenchUserInfo(userSubjectId = disabledUser, disabledUserEmail)
  val enrolledUserInfo = WorkbenchUserInfo(userSubjectId = enrolledUser, enrolledUserEmail)
  val terminatedUserInfo = WorkbenchUserInfo(userSubjectId = terminatedUser, terminatedUserEmail)

  val dummy1UserRegInfo = RegistrationInfo(dummy1UserInfo, workbenchEnabled)
  val dummy2UserRegInfo = RegistrationInfo(dummy2UserInfo, workbenchEnabled)
  val enabledUserRegInfo = RegistrationInfo(enabledUserInfo, workbenchEnabled)
  val disabledUserRegInfo = RegistrationInfo(disabledUserInfo, workbenchEnabled)
  val enrolledUserRegInfo = RegistrationInfo(enrolledUserInfo, workbenchEnabled)
  val terminatedUserRegInfo = RegistrationInfo(terminatedUserInfo, workbenchEnabled)

  val registrationInfoByEmail = Map(
    dummy1User -> dummy1UserRegInfo,
    dummy2User -> dummy2UserRegInfo,
    enabledUser -> enabledUserRegInfo,
    disabledUser -> disabledUserRegInfo,
    enrolledUser -> enrolledUserRegInfo,
    terminatedUser -> terminatedUserRegInfo)
}
