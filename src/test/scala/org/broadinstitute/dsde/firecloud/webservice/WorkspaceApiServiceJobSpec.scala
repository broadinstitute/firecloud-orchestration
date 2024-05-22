package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route.seal
import org.broadinstitute.dsde.firecloud.dataaccess.{CwdsDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.model.{CwdsListResponse, ModelSchema, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PermissionReportService, WorkspaceService}
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.rawls.model._
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{clearInvocations, times, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar

import java.util.UUID
import scala.concurrent.ExecutionContext

class WorkspaceApiServiceJobSpec extends BaseServiceSpec with WorkspaceApiService with MockitoSugar with
  BeforeAndAfterEach {

  // mocks for Cwds and Rawls
  private val mockitoCwdsDao = mock[CwdsDAO]

  // setup for the WorkspaceApiService routes
  override val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  override val workspaceServiceConstructor: WithAccessToken => WorkspaceService = WorkspaceService.constructor(app.copy())
  override val permissionReportServiceConstructor: UserInfo => PermissionReportService = PermissionReportService
    .constructor(app)
  override val entityServiceConstructor: ModelSchema => EntityService = EntityService.constructor(app.copy(cwdsDAO = mockitoCwdsDao))

  // dummy data for use in tests below
  private val dummyUserId = "1234"
  private val workspace = WorkspaceDetails("namespace", "name", MockRawlsDAO.mockWorkspaceId, "buckety_bucket", Some
  ("wf-collection"), DateTime.now(), DateTime.now(), "my_workspace_creator", Some(Map()), //attributes
    isLocked = false, //locked
    Some(Set.empty), //authorizationDomain
    WorkspaceVersions.V2, GoogleProjectId("googleProject"), Some(GoogleProjectNumber("googleProjectNumber")), Some
    (RawlsBillingAccountName("billingAccount")), None, None, Option(DateTime.now()), None, None, WorkspaceState.Ready)
  private val importList = List(
    CwdsListResponse(UUID.randomUUID().toString, "running", "filetype1", None),
    CwdsListResponse(UUID.randomUUID().toString, "error", "filetype2", Some("my error message")),
    CwdsListResponse(UUID.randomUUID().toString, "success", "filetype3", None)
  )

  // a few shortcuts for accessing the routes
  private final val workspacesRoot = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  private final val pfbImportPath = workspacesRoot + "/%s/%s/importPFB".format(workspace.namespace, workspace.name)
  private final val importJobPath = workspacesRoot + "/%s/%s/importJob".format(workspace.namespace, workspace.name)

  "WorkspaceService list-jobs API" - {
    // test both the importPFB and importJob routes
    List(pfbImportPath, importJobPath) foreach { pathUnderTest =>
      s"for path $pathUnderTest" - {
        // test running_only=true and running_only=false
        List(true, false) foreach { runningOnly =>
          s"should call CwdsDAO.listJobs with running_only=$runningOnly" in {
            // reset mock invocation counts and configure its return value
            clearInvocations(mockitoCwdsDao)
            when(mockitoCwdsDao.listJobsV1(any[String], any[Boolean])(any[UserInfo])).thenReturn(importList)
            // execute the route
            (Get(s"$pathUnderTest?running_only=$runningOnly") ~> dummyUserIdHeaders(dummyUserId) ~> seal
            (workspaceRoutes)) ~> check {
              // route should return 200 OK
              status should equal(OK)
              // we should have invoked the CwdsDAO correctly
              verify(mockitoCwdsDao, times(1)).listJobsV1(ArgumentMatchers.eq(workspace.workspaceId),
                ArgumentMatchers.eq(runningOnly))(any[UserInfo])
            }
          }
        }
      }
    }
  }
}
