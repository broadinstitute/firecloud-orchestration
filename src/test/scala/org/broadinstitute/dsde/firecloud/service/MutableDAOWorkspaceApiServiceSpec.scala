package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.webservice.WorkspaceApiService
import org.broadinstitute.dsde.rawls.model.Workspace
import org.joda.time.DateTime
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers}
import spray.http.StatusCodes.OK
import spray.http._
import spray.testkit.ScalatestRouteTest


class MockApplication(request: HttpRequest) extends BaseServiceSpec with WorkspaceApiService {

  def actorRefFactory: ActorSystem = system
  val workspaceServiceConstructor: (WithAccessToken) => WorkspaceService = WorkspaceService.constructor(app)
  val permissionReportServiceConstructor: (UserInfo) => PermissionReportService = PermissionReportService.constructor(app)

  lazy val checkRequest: (HttpResponse, StatusCode) = {
    request ~> dummyUserIdHeaders("1234") ~> sealRoute(workspaceRoutes) ~> check {(response, status)}
  }

  lazy val isIndexDocumentInvoked: Boolean = {
    this.searchDao.indexDocumentInvoked
  }

}


class MutableDAOWorkspaceApiServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers with BeforeAndAfterEach {

  val workspace = Workspace(
    "namespace",
    "name",
    Set.empty,
    "workspace_id",
    "buckety_bucket",
    DateTime.now(),
    DateTime.now(),
    "my_workspace_creator",
    Map(), //attributes
    Map(), //acls
    Map(), //authdomain acls
    isLocked = false //locked
  )
  private final val workspacesRoot = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  private final val updateAttributesPath = workspacesRoot + "/%s/%s/updateAttributes".format(workspace.namespace, workspace.name)
  private final val setAttributesPath = workspacesRoot + "/%s/%s/setAttributes".format(workspace.namespace, workspace.name)

  "Workspace updateAttributes tests" - {

    "when calling PATCH on workspaces/*/*/updateAttributes path" - {

      "should 200 OK if the payload is ok" in {
        val request = Patch(updateAttributesPath,
          HttpEntity(MediaTypes.`application/json`, """[
                                                      |  {
                                                      |    "op": "AddUpdateAttribute",
                                                      |    "attributeName": "library:dataCategory",
                                                      |    "addUpdateAttribute": "test-attribute-value"
                                                      |  }
                                                      |]""".stripMargin))
        val app = new MockApplication(request)
        val (response, status) = app.checkRequest
        status should equal(OK)
        assert(!app.isIndexDocumentInvoked, "Should not be indexing an unpublished WS")
      }

      "should republish if the document is already published" in {

        val request = Patch(workspacesRoot + "/%s/%s/updateAttributes".format(WorkspaceApiServiceSpec.publishedWorkspace.namespace, WorkspaceApiServiceSpec.publishedWorkspace.name),
          HttpEntity(MediaTypes.`application/json`, """[
                                                      |  {
                                                      |    "op": "AddUpdateAttribute",
                                                      |    "attributeName": "library:dataCategory",
                                                      |    "addUpdateAttribute": "test-attribute-value"
                                                      |  }
                                                      |]""".stripMargin))
        val app = new MockApplication(request)
        val (response, status) = app.checkRequest
        status should equal(OK)
        assert(app.isIndexDocumentInvoked, "Should have republished this published WS when changing attributes")
      }

    }
  }

  "Workspace setAttributes tests" - {

    "when calling PATCH on workspaces/*/*/setAttributes path" - {

      "should 200 OK if the payload is ok" in {
        val request = Patch(setAttributesPath,
          HttpEntity(MediaTypes.`application/json`, """{"description": "something",
                                                      | "array": [1, 2, 3]
                                                      | }""".stripMargin))
        val app = new MockApplication(request)
        val (response, status) = app.checkRequest
        status should equal(OK)
        assert(!app.isIndexDocumentInvoked, "Should not be indexing an unpublished WS")
      }

      "should republish if the document is already published" in {
        val request = Patch(workspacesRoot + "/%s/%s/setAttributes".format(WorkspaceApiServiceSpec.publishedWorkspace.namespace, WorkspaceApiServiceSpec.publishedWorkspace.name),
          HttpEntity(MediaTypes.`application/json`, """{"description": "something",
                                                      | "array": [1, 2, 3]
                                                      | }""".stripMargin))
        val app = new MockApplication(request)
        val (response, status) = app.checkRequest
        status should equal(OK)
        assert(app.isIndexDocumentInvoked, "Should have republished this published WS when changing attributes")
      }

    }

  }

}
