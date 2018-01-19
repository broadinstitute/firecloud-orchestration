package org.broadinstitute.dsde.firecloud.webservice

import java.time.temporal.ChronoUnit

import com.google.api.client.googleapis.json.{GoogleJsonError, GoogleJsonResponseException}
import com.google.api.client.http.{HttpHeaders, HttpResponseException}
import com.google.api.services.sheets.v4.model.ValueRange
import org.broadinstitute.dsde.firecloud.{FireCloudConfig, FireCloudException, FireCloudExceptionWithErrorReport}
import org.broadinstitute.dsde.firecloud.dataaccess.{HttpSamDAO, HttpThurloeDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.mock.{MockGoogleServicesDAO, MockUtils}
import org.broadinstitute.dsde.firecloud.mock.MockUtils.thurloeServerPort
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impProfileWrapper
import org.broadinstitute.dsde.firecloud.model.Trial.ProjectRoles.ProjectRole
import org.broadinstitute.dsde.firecloud.model.Trial.TrialStates.{Disabled, Enabled, Enrolled}
import org.broadinstitute.dsde.firecloud.model.Trial._
import org.broadinstitute.dsde.firecloud.model.{FireCloudKeyValue, ProfileWrapper, RegistrationInfo, UserInfo, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, TrialService}
import org.broadinstitute.dsde.firecloud.trial.ProjectManager
import org.broadinstitute.dsde.firecloud.trial.ProjectManagerSpec.ProjectManagerSpecTrialDAO
import org.broadinstitute.dsde.rawls.model.{ErrorReport, RawlsBillingProjectName, RawlsUserEmail}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import spray.http.HttpMethods.{POST, PUT}
import spray.http.StatusCodes
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing.RequestContext

import scala.concurrent.Future
import scala.util.Success

final class TrialApiServiceSpec extends BaseServiceSpec with UserApiService with TrialApiService {
  import TrialApiServiceSpec._

  // The user enrollment endpoint tested in this class lives in UserApiService,
  // but it is tested here since it is used for the trial feature.

  def actorRefFactory = system
  val localThurloeDao = new TrialApiServiceSpecThurloeDAO
  val localSamDao = new TrialApiServiceSpecSamDAO
  val localTrialDao = new TrialApiServiceSpecTrialDAO
  val localRawlsDao = new TrialApiServiceSpecRawlsDAO
  val localGoogleDao = new TrialApiServiceSpecGoogleDAO

  val trialProjectManager = system.actorOf(ProjectManager.props(app.rawlsDAO, app.trialDAO, app.googleServicesDAO), "trial-project-manager")
  val trialServiceConstructor:() => TrialService =
    TrialService.constructor(
      app.copy(
        thurloeDAO = localThurloeDao,
        samDAO = localSamDao,
        trialDAO = localTrialDao,
        rawlsDAO = localRawlsDao,
        googleServicesDAO = localGoogleDao),
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
      (registeredUser, noTrialProps),
      (dummy2User, dummy2Props),
      (disabledUser, disabledProps),
      (enabledUser, enabledProps),
      (enabledButNotAgreedUser, enabledButNotAgreedProps),
      (enrolledUser, enrolledProps),
      (terminatedUser, terminatedProps),
      (finalizedUser, finalizedProps))

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

    // For testing Thurloe internal errors
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

  "User-initiated free trial" - {
    "service agreement endpoint" - {
      val userAgreementPath = "/api/profile/trial/userAgreement"
      val allButEnabledUsers = Seq(disabledUser, enrolledUser, terminatedUser, finalizedUser)

      "failing due to Thurloe error should return a server error to the user" in {
        Put(userAgreementPath) ~> dummyUserIdHeaders(dummy1User) ~> userServiceRoutes ~> check {
          status should equal(InternalServerError)
          assert(localThurloeDao.agreedUsers.isEmpty)
        }
      }

      allHttpMethodsExcept(PUT) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)(userAgreementPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
            assert(!handled)
            assert(localThurloeDao.agreedUsers.isEmpty)
          }
        }
      }

      allButEnabledUsers foreach { user =>
        s"should not allow a $user" in {
          Put(userAgreementPath) ~> dummyUserIdHeaders(user) ~> userServiceRoutes ~> check {
            status should equal(Forbidden)
            assert(localThurloeDao.agreedUsers.isEmpty)
          }
        }
      }

      "attempting to agree to terms as enabled user" in {
        Put(userAgreementPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          status should equal(NoContent)
          assert(localThurloeDao.agreedUsers == Seq("enabled-user"))
        }
      }
    }

    val trialPathBase = "/api/profile/trial"

    val enrollPaths = Seq(trialPathBase, s"$trialPathBase?operation=enroll")

    enrollPaths foreach { enrollPath =>
      s"enrollment endpoint $enrollPath" - {
        allHttpMethodsExcept(POST) foreach { method =>
          s"should reject ${method.toString} method" in {
            new RequestBuilder(method)(enrollPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
              assert(!handled)
            }
          }
        }
      }

      s"enrollment as a disabled user via $enrollPath" - {
        "should be a BadRequest" in {
          Post(enrollPath) ~> dummyUserIdHeaders(disabledUser) ~> userServiceRoutes ~> check {
            status should equal(BadRequest)
          }
        }
      }

      s"enrollment as an enabled user that agreed to terms via $enrollPath" - {
        "should be NoContent success" in {
          Post(enrollPath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
            assertResult(NoContent, response.entity.asString) {
              status
            }
            assert(localRawlsDao.billingProjectAdds == Map("testproject" -> "random@site.com"))
          }
        }
      }

      s"enrollment as an enabled user that DID NOT agree to terms via $enrollPath" - {
        "should be Forbidden" in {
          Post(enrollPath) ~> dummyUserIdHeaders(enabledButNotAgreedUser) ~> userServiceRoutes ~> check {
            assertResult(Forbidden, response.entity.asString) {
              status
            }
          }
        }
      }

      s"enrollment as an enrolled user via $enrollPath" - {
        "should be a BadRequest" in {
          Post(enrollPath) ~> dummyUserIdHeaders(enrolledUser) ~> userServiceRoutes ~> check {
            status should equal(BadRequest)
          }
        }
      }

      s"enrollment as a terminated user via $enrollPath" - {
        "should be a BadRequest" in {
          Post(enrollPath) ~> dummyUserIdHeaders(terminatedUser) ~> userServiceRoutes ~> check {
            status should equal(BadRequest)
          }
        }
      }
    }

    val finalizePath = s"$trialPathBase?operation=finalize"

    s"finalization endpoint $finalizePath" - {
      allHttpMethodsExcept(POST) foreach { method =>
        s"should reject ${method.toString} method" in {
          new RequestBuilder(method)(finalizePath) ~> dummyUserIdHeaders(terminatedUser) ~> userServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }
    }

    s"finalization as a terminated user via $finalizePath" - {
      "should be NoContent success" in {
        Post(finalizePath) ~> dummyUserIdHeaders(terminatedUser) ~> userServiceRoutes ~> check {
          assertResult(NoContent, response.entity.asString) {
            status
          }
        }
      }
    }

    val usersDisallowedForFinalization = Seq(enabledUser, disabledUser, enrolledUser, finalizedUser)
    usersDisallowedForFinalization foreach { disallowedUser =>
      s"finalization as a $disallowedUser via $finalizePath" - {
        "should be a BadRequest" in {
          Post(finalizePath) ~> dummyUserIdHeaders(disallowedUser) ~> userServiceRoutes ~> check {
            status should equal(BadRequest)
          }
        }
      }
    }

    val invalidPaths = Seq(s"$trialPathBase?operation=", s"$trialPathBase?operation=invalid")

    invalidPaths foreach { path =>
      s"invalid operations via $path should be a BadRequest" in {
        Post(finalizePath) ~> dummyUserIdHeaders(enabledUser) ~> userServiceRoutes ~> check {
          status should equal(BadRequest)
        }
      }
    }
  }

  "Campaign manager user enable/disable/terminate endpoint" - {
    val enablePath    = "/trial/manager/enable"
    val disablePath   = "/trial/manager/disable"
    val terminatePath = "/trial/manager/terminate"
    val invalidPath = "/trial/manager/unsupported-operation"

    val dummy1UserEmails = Seq(dummy1User)
    val dummy2UserEmails = Seq(dummy2User)
    val disabledUserEmails = Seq(disabledUser)
    val enabledUserEmails = Seq(enabledUser)
    val enrolledUserEmails = Seq(enrolledUser)
    val terminatedUserEmails = Seq(terminatedUser)
    val registeredUserEmails = Seq(registeredUser)
    val nonRegisteredUserEmails = Seq(unregisteredUser)

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
          assertResult(OK, response.entity.asString) { status }
          val resp = response.entity.asString
          assert(resp.contains("ServerError:"))
          assert(resp.contains("Unable to get user KVPs from profile service"))
        }
      }

      "preventing us from saving user trial status should be properly communicated to the user" in {
        // Utilizes localThurloeDao
        Post(disablePath, dummy2UserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assertResult(OK, response.entity.asString) { status }
          assert(response.entity.asString.contains("ServerError: ErrorReport(Thurloe,Unable to update user profile"))
        }
      }
    }

    "Attempting an invalid operation should not be handled" in {
      Post(invalidPath, disabledUserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        assert(!handled)
      }
    }

    "Multi-User Status Updates" - {
      "Attempting to enable multiple users in various states should return a map of status lists" in {
        val assortmentOfUserEmails = Seq(disabledUser, enabledUser, enrolledUser, terminatedUser,
            finalizedUser, registeredUser, dummy1User, dummy2User)

        Post(enablePath, assortmentOfUserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          val enableResponse = responseAs[Map[String, Set[String]]].map(_.swap)

          assert(enableResponse(Set(enabledUser, dummy2User)) === StatusUpdate.NoChangeRequired.toString)
          assert(enableResponse(Set(disabledUser, registeredUser)) === StatusUpdate.Success.toString)
          assert(enableResponse(Set(enrolledUser)).contains("Failure: Cannot transition"))
          assert(enableResponse(Set(terminatedUser)).contains("Failure: Cannot transition"))
          assert(enableResponse(Set(finalizedUser)).contains("Failure: Cannot transition"))
          assert(enableResponse(Set(dummy1User))
            .contains("ServerError: ErrorReport(Thurloe,Unable to get user KVPs from profile service,Some(500 Internal Server Error)"))

          assertResult(OK) {
            status
          }
        }
      }

      "Attempting to disable multiple users in various states should return a map of status lists" in {
        val assortmentOfUserEmails = Seq(disabledUser, enabledUser, enrolledUser, terminatedUser,
            finalizedUser, registeredUser, dummy1User, dummy2User)

        Post(disablePath, assortmentOfUserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          val disableResponse = responseAs[Map[String, Set[String]]].map(_.swap)

          assert(disableResponse(Set(enabledUser)) === StatusUpdate.Success.toString)
          assert(disableResponse(Set(disabledUser)) === StatusUpdate.NoChangeRequired.toString)
          assert(disableResponse(Set(enrolledUser)).contains("Failure: Cannot transition"))
          assert(disableResponse(Set(terminatedUser)).contains("Failure: Cannot transition"))
          assert(disableResponse(Set(finalizedUser)).contains("Failure: Cannot transition"))
          assert(disableResponse(Set(registeredUser)).contains("Failure: Cannot transition"))
          assert(disableResponse(Set(dummy1User))
            .contains("ServerError: ErrorReport(Thurloe,Unable to get user KVPs from profile service,Some(500 Internal Server Error)"))
          assert(disableResponse(Set(dummy2User))
            .contains("ServerError: ErrorReport(Thurloe,Unable to update user profile,Some(500 Internal Server Error)"))

          assertResult(OK) {
            status
          }
        }
      }

      "Attempting to terminate multiple users in various states should return a map of status lists" in {
        val assortmentOfUserEmails =
          Seq(disabledUser, enabledUser, enrolledUser, terminatedUser,
            finalizedUser, registeredUser, dummy1User, dummy2User)

        Post(terminatePath, assortmentOfUserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          val terminateResponse = responseAs[Map[String, Set[String]]].map(_.swap)

          assert(terminateResponse(Set(enabledUser)).contains("Failure: Cannot transition"))
          assert(terminateResponse(Set(dummy2User)).contains("Failure: Cannot transition"))
          assert(terminateResponse(Set(disabledUser)).contains("Failure: Cannot transition"))
          assert(terminateResponse(Set(finalizedUser)).contains("Failure: Cannot transition"))
          assert(terminateResponse(Set(enrolledUser)) === StatusUpdate.Success.toString)
          assert(terminateResponse(Set(terminatedUser)) === StatusUpdate.NoChangeRequired.toString)
          assert(terminateResponse(Set(registeredUser)).contains("Failure: Cannot transition"))
          assert(terminateResponse(Set(dummy1User))
            .contains("ServerError: ErrorReport(Thurloe,Unable to get user KVPs from profile service,Some(500 Internal Server Error)"))

          assertResult(OK) {
            status
          }
        }
      }
    }

    "Attempting to enable a non registered user should fail" in {
      Post(enablePath, nonRegisteredUserEmails) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        assertResult(OK, response.entity.asString) { status }
        assert(response.entity.asString.contains("Failure: User not registered"))
      }
    }

    // Positive and negative tests for status transitions among {enable, disable, terminate}
    val allManagerOperations = Seq(enablePath, disablePath, terminatePath)

    // Define the operations that should succeed. All other operations should fail.
    val successes: Map[Seq[String], Seq[String]] = Map(
      disabledUserEmails -> Seq(enablePath, disablePath),
      enabledUserEmails -> Seq(enablePath, disablePath),
      enrolledUserEmails -> Seq(terminatePath),
      terminatedUserEmails -> Seq(terminatePath),
      registeredUserEmails -> Seq(enablePath, disablePath)
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
            assertResult(OK, response.entity.asString) { status }
            assert(response.entity.asString.contains("Failure: Cannot transition from"))
          }
        }
      }
    }

    "attempting to enable more users than available projects should fail" in {
      Post(enablePath, Seq.fill(101)("foo")) ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
        assertResult(BadRequest, response.entity.asString) { status }
        assert(response.entity.asString.contains("You are enabling 101 users, but there are only 100 projects available. " +
          "Please create more projects."))
        // limit of 100 defined in TrialApiServiceSpecTrialDAO
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
          new RequestBuilder(method)("/trial/manager/report/valid") ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
            assert(!handled)
          }
        }
      }

      "should succeed with an update request" in {
        Put("/trial/manager/report/valid") ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          assert(status.isSuccess)
          assertResult(OK) {status}
        }
      }

      "invalid spreadsheet id should fail an update request" in {
        Put("/trial/manager/report/invalid") ~> dummyUserIdHeaders(manager) ~> trialApiServiceRoutes ~> check {
          println(response)
          assert(status.isFailure)
          assertResult(NotFound) {status}
        }
      }

      "non-campaign manager should fail an update request" in {
        Put("/trial/manager/report/valid") ~> dummyUserIdHeaders(unauthorizedUser) ~> trialApiServiceRoutes ~> check {
          assert(status.isFailure)
          assertResult(Forbidden) {status}
        }
      }

    }

  }

  class TrialApiServiceSpecTrialDAO extends ProjectManagerSpecTrialDAO {
    override def claimProjectRecord(userInfo: WorkbenchUserInfo, randomizationFactor: Int = 20): TrialProject = {
      TrialProject(RawlsBillingProjectName(userInfo.userEmail), true, Some(userInfo), Some(CreationStatuses.Ready))
    }

    override def releaseProjectRecord(projectName: RawlsBillingProjectName): TrialProject = {
      TrialProject(projectName)
    }

    override def countProjects: Map[String, Long] = Map("available" -> 100)
  }

  /** Used by positive and negative tests where `saveTrialStatus` is called */
  final class TrialApiServiceSpecThurloeDAO extends HttpThurloeDAO {

    private[webservice] var agreedUsers = Seq.empty[String]

    override def saveTrialStatus(forUserId: String, callerToken: WithAccessToken, trialStatus: UserTrialStatus) = {
      if (trialStatus.userAgreed) {
        agreedUsers = agreedUsers :+ forUserId
      }

      // Note: because HttpThurloeDAO catches exceptions, the assertions here will
      // result in InternalServerErrors instead of appearing nicely in unit test output.
      forUserId match {
        case `enabledUser` => trialStatus.state match {
          case Some(Enrolled) =>
            val expectedExpirationDate = trialStatus.enrolledDate.plus(FireCloudConfig.Trial.durationDays, ChronoUnit.DAYS)

            assert(trialStatus.enrolledDate.toEpochMilli > 0)
            assert(trialStatus.expirationDate.toEpochMilli > 0)
            assertResult( expectedExpirationDate ) { trialStatus.expirationDate }
            assert(trialStatus.terminatedDate.toEpochMilli === 0)

            Future.successful(Success(()))
          case Some(Enabled) =>
            Future.successful(Success(()))
          case Some(Disabled) =>
            Future.successful(Success(()))
          case _ =>
            Future.failed(new IllegalArgumentException(s"Enabled status can only transition to Enrolled or Disabled"))
        }
        case `disabledUser` | `registeredUser` => {
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
        case `terminatedUser` => {
          assertResult(Some(TrialStates.Finalized)) { trialStatus.state }
          assert(trialStatus.enabledDate.toEpochMilli > 0)
          assert(trialStatus.enrolledDate.toEpochMilli > 0)
          assert(trialStatus.terminatedDate.toEpochMilli > 0)
          assert(trialStatus.expirationDate.toEpochMilli > 0)

          Future.successful(Success(()))
        }
        case `dummy2User` => { // Mocking Thurloe status saving failures
          Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport.apply(StatusCodes.InternalServerError, new FireCloudException(s"Unable to update user profile"))))
        }
        case _ => {
          fail("Should only be updating registered, enabled, disabled, enrolled or terminated users")
        }
      }
    }
  }

  final class TrialApiServiceSpecSamDAO extends HttpSamDAO {
    override def adminGetUserByEmail(email: RawlsUserEmail) = {
      email.value match {
        case x if registrationInfoByEmail.keySet.contains(x)=> Future.successful(registrationInfoByEmail(email.value))
        case _ => throw new FireCloudExceptionWithErrorReport(ErrorReport(NotFound, ""))
      }
    }
  }

  /** Used to ensure that manager endpoints only serve managers */
  final class TrialApiServiceSpecRawlsDAO extends MockRawlsDAO {

    private[webservice] var billingProjectAdds = Map.empty[String, String]

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

    override def addUserToBillingProject(projectId: String, role: ProjectRole, email: String)(implicit userToken: WithAccessToken): Future[Boolean] = {
      billingProjectAdds = billingProjectAdds + (projectId -> email)
      Future(true)
    }
  }

  final class TrialApiServiceSpecGoogleDAO extends MockGoogleServicesDAO {

    override def updateSpreadsheet(requestContext: RequestContext, userInfo: UserInfo, spreadsheetId: String, content: ValueRange): JsObject = {
      spreadsheetId match {
        case "invalid" =>
          // A lot of java overhead to generate the right exception...
          val headers: HttpHeaders = new HttpHeaders().setAuthorization("Bearer mF_9.B5f-4.1JqM").setContentType("application/json")
          val builder: HttpResponseException.Builder = new HttpResponseException.Builder(404, "NOT_FOUND", headers)
          val details: GoogleJsonError = new GoogleJsonError().set("code", 404).set("domain", "global").set("message", "Requested entity was not found.").set("reason", "notFound")
          throw new GoogleJsonResponseException(builder, details)
        case _ => spreadsheetUpdateJson
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
  val finalizedUser = "finalized-user"
  val registeredUser = "registered-user"
  val unregisteredUser = "unregistered-user"

  val noTrialProps = Map.empty[String,String]

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
  val finalizedProps = Map(
    "trialState" -> "Finalized",
    "trialEnabledDate" -> "555",
    "trialEnrolledDate" -> "666",
    "trialTerminatedDate" -> "777",
    "trialExpirationDate" -> "888"
  )

  val workbenchEnabled = WorkbenchEnabled(google = true, ldap = true, allUsersGroup = true)

  val dummy1UserEmail = "dummy1-user-email"
  val dummy2UserEmail = "dummy2-user-email"
  val enabledUserEmail = "enabled-user-email"
  val disabledUserEmail = "disabled-user-email"
  val enrolledUserEmail = "enrolled-user-email"
  val terminatedUserEmail = "terminated-user-email"
  val finalizedUserEmail = "finalized-user-email"
  val registeredUserEmail = "registered-user-email"

  val dummy1UserInfo = WorkbenchUserInfo(userSubjectId = dummy1User, dummy1UserEmail)
  val dummy2UserInfo = WorkbenchUserInfo(userSubjectId = dummy2User, dummy2UserEmail)
  val enabledUserInfo = WorkbenchUserInfo(userSubjectId = enabledUser, enabledUserEmail)
  val disabledUserInfo = WorkbenchUserInfo(userSubjectId = disabledUser, disabledUserEmail)
  val enrolledUserInfo = WorkbenchUserInfo(userSubjectId = enrolledUser, enrolledUserEmail)
  val terminatedUserInfo = WorkbenchUserInfo(userSubjectId = terminatedUser, terminatedUserEmail)
  val finalizedUserInfo = WorkbenchUserInfo(userSubjectId = finalizedUser, finalizedUserEmail)
  val registeredUserInfo = WorkbenchUserInfo(userSubjectId = registeredUser, registeredUserEmail)

  val dummy1UserRegInfo = RegistrationInfo(dummy1UserInfo, workbenchEnabled)
  val dummy2UserRegInfo = RegistrationInfo(dummy2UserInfo, workbenchEnabled)
  val enabledUserRegInfo = RegistrationInfo(enabledUserInfo, workbenchEnabled)
  val disabledUserRegInfo = RegistrationInfo(disabledUserInfo, workbenchEnabled)
  val enrolledUserRegInfo = RegistrationInfo(enrolledUserInfo, workbenchEnabled)
  val terminatedUserRegInfo = RegistrationInfo(terminatedUserInfo, workbenchEnabled)
  val finalizedUserRegInfo = RegistrationInfo(finalizedUserInfo, workbenchEnabled)
  val registeredUserRegInfo = RegistrationInfo(registeredUserInfo, workbenchEnabled)

  val registrationInfoByEmail = Map(
    dummy1User -> dummy1UserRegInfo,
    dummy2User -> dummy2UserRegInfo,
    enabledUser -> enabledUserRegInfo,
    disabledUser -> disabledUserRegInfo,
    enrolledUser -> enrolledUserRegInfo,
    terminatedUser -> terminatedUserRegInfo,
    finalizedUser -> finalizedUserRegInfo,
    registeredUser -> registeredUserRegInfo)
}
