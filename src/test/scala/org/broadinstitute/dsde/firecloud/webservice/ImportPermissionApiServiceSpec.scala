package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.impUserImportPermission
import org.broadinstitute.dsde.firecloud.model.Project.{CreationStatuses, ProjectRoles, RawlsBillingProjectMembership}
import org.broadinstitute.dsde.firecloud.model.{Project, UserImportPermission, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, UserService}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.rawls.model._

import scala.concurrent.{ExecutionContext, Future}

class ImportPermissionApiServiceSpec extends BaseServiceSpec with UserApiService with SprayJsonSupport {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val testApp = app.copy(rawlsDAO = new ImportPermissionMockRawlsDAO)

  val userServiceConstructor:(UserInfo) => UserService = UserService.constructor(testApp)

  "UserService /api/profile/importstatus endpoint tests" - {

    val endpoint = "/api/profile/importstatus"

    "should reject all but GET" in {
      allHttpMethodsExcept(HttpMethods.GET) foreach { method =>
        checkIfPassedThrough(userServiceRoutes, method, endpoint, toBeHandled = false)
      }
    }
    "should accept GET" in {
      Get(endpoint) ~> dummyUserIdHeaders("foo","noWorkspaces;noProjects") ~> userServiceRoutes ~> check {
        assert(handled)
      }
    }
    "should return billingProject: true if user has at least one billing project" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","noWorkspaces;hasProjects") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].billingProject shouldBe true
      }
    }
    "should return billingProject: false if user has no billing projects" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid", "noWorkspaces;noProjects") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].billingProject shouldBe false
      }
    }
    "should return billingProject: false if user has billing projects, but none that are ready" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid", "noWorkspaces;projectsNotReady") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].billingProject shouldBe false
      }
    }
    "should return writableWorkspace: true if user has a writable workspace" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","hasWorkspaces;noProjects") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].writableWorkspace shouldBe true
      }
    }
    "should return writableWorkspace: false if user has no workspaces" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","noWorkspaces;noProjects") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].writableWorkspace shouldBe false
      }
    }
    "should return writableWorkspace: false if user has workspaces, but none that are writable" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","onlyReadableWorkspaces;noProjects") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].writableWorkspace shouldBe false
      }
    }

    "should return both writableWorkspace: true and billingProject: true if both conditions are satisfied" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","hasWorkspaces;hasProjects") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].billingProject shouldBe true
        responseAs[UserImportPermission].writableWorkspace shouldBe true
      }
    }
    "should return both writableWorkspace: false and billingProject: false if both conditions failed" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","onlyReadableWorkspaces;projectsNotReady") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(OK)
        responseAs[UserImportPermission].billingProject shouldBe false
        responseAs[UserImportPermission].writableWorkspace shouldBe false
      }
    }

    "should propagate an error if the call to get workspaces fails" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","thisWillError;hasProjects") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(InternalServerError)
        val err:ErrorReport = responseAs[ErrorReport]
        err.message shouldBe "intentional exception for getWorkspaces catchall case"
      }
    }
    "should propagate an error if the call to get billing projects fails" in {
      Get(endpoint) ~> dummyUserIdHeaders("userid","hasWorkspaces;thisWillError") ~> sealRoute(userServiceRoutes) ~> check {
        status should equal(InternalServerError)
        val err:ErrorReport = responseAs[ErrorReport]
        err.message shouldBe "intentional exception for getProjects catchall case"
      }
    }
  }

}

class ImportPermissionMockRawlsDAO extends MockRawlsDAO {

  override def getProjects(implicit userToken: WithAccessToken): Future[Seq[Project.RawlsBillingProjectMembership]] = {
    parseTestToken(userToken)._2 match {
      case "hasProjects" => Future.successful(Seq(
        RawlsBillingProjectMembership(RawlsBillingProjectName("projectone"), ProjectRoles.User, CreationStatuses.Ready, None),
        RawlsBillingProjectMembership(RawlsBillingProjectName("projecttwo"), ProjectRoles.Owner, CreationStatuses.Creating, None)
      ))
      case "projectsNotReady" => Future.successful(Seq(
        RawlsBillingProjectMembership(RawlsBillingProjectName("projectone"), ProjectRoles.User, CreationStatuses.Creating, None),
        RawlsBillingProjectMembership(RawlsBillingProjectName("projecttwo"), ProjectRoles.Owner, CreationStatuses.Creating, None)

      ))
      case "noProjects" => Future.successful(Seq.empty[RawlsBillingProjectMembership])
      case _ => Future.failed(new FireCloudException("intentional exception for getProjects catchall case"))
    }
  }

  override def getWorkspaces(implicit userInfo: WithAccessToken): Future[Seq[WorkspaceListResponse]] = {
    parseTestToken(userInfo)._1 match {
      case "hasWorkspaces" => Future.successful(Seq(
        WorkspaceListResponse(WorkspaceAccessLevels.ProjectOwner, Some(true), Some(true), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
        WorkspaceListResponse(WorkspaceAccessLevels.Read, Some(false), Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
        WorkspaceListResponse(WorkspaceAccessLevels.Owner, Some(true), Some(true), publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
        WorkspaceListResponse(WorkspaceAccessLevels.NoAccess, Some(false), Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false)
      ))
      case "onlyReadableWorkspaces" => Future.successful(Seq(
        WorkspaceListResponse(WorkspaceAccessLevels.Read, Some(false), Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
        WorkspaceListResponse(WorkspaceAccessLevels.Read, Some(false), Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
        WorkspaceListResponse(WorkspaceAccessLevels.Read, Some(false), Some(false), publishedRawlsWorkspaceWithAttributes, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false),
        WorkspaceListResponse(WorkspaceAccessLevels.NoAccess, Some(false), Some(false), newWorkspace, Some(WorkspaceSubmissionStats(None, None, runningSubmissionsCount = 0)), false)
      ))
      case "noWorkspaces" => Future.successful(Seq.empty[WorkspaceListResponse])
      case _ => Future.failed(new FireCloudException("intentional exception for getWorkspaces catchall case"))
    }
  }

  // this is hacky, but the only argument to getProjects and getWorkspaces is the access token. Therefore,
  // we need to encode our test criteria into a string, and we can do so using a delimiter.
  private def parseTestToken(userInfo: WithAccessToken): (String,String) = {
    val tokenParts = userInfo.accessToken.token.split(";")
    assert(tokenParts.length == 2)
    (tokenParts(0), tokenParts(1))
  }

}
