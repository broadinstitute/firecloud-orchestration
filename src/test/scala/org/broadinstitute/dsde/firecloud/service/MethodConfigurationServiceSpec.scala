package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.model.{WorkspaceEntity, ErrorReport, CopyConfigurationIngest}
import spray.http.StatusCodes._

import spray.httpx.SprayJsonSupport._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

class MethodConfigurationServiceSpec extends ServiceSpec with MethodConfigurationService {

  import ErrorReport.errorReportRejectionHandler

  def actorRefFactory = system

  private def methodConfigUrl(workspaceEntity: WorkspaceEntity) = s"/workspaces/%s/%s/method_configs/%s/%s".format(
    workspaceEntity.namespace.get,
    workspaceEntity.name.get,
    workspaceEntity.namespace.get,
    workspaceEntity.name.get)

  private final val validMethodConfigUrl = methodConfigUrl(MockWorkspaceServer.mockValidWorkspace)
  private final val invalidMethodConfigUrl = methodConfigUrl(MockWorkspaceServer.mockInvalidWorkspace)

  private final val validCopyFromRepoUrl = s"/workspaces/%s/%s/method_configs/copyFromMethodRepo".format(
    MockWorkspaceServer.mockValidWorkspace.namespace.get,
    MockWorkspaceServer.mockValidWorkspace.name.get
  )
  private final val validConfigurationCopyFormData = CopyConfigurationIngest(
    configurationNamespace = Option("namespace"),
    configurationName = Option("name"),
    configurationSnapshotId = Option(1),
    destinationNamespace = Option("namespace"),
    destinationName = Option("new-name")
  )
  private final val invalidConfigurationCopyFormData = new CopyConfigurationIngest(None, None, None, None, None)

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  "MethodConfigurationService" - {

    "when calling DELETE on the /workspaces/*/*/method_configs/*/* with a valid path" - {
      "Successful Request (204, NoContent) response is returned" in {
        Delete(validMethodConfigUrl) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NoContent)
        }
      }
    }

    "when calling DELETE on the /workspaces/*/*/method_configs/*/* with an invalid path" - {
      "NotFound response is returned" in {
        Delete(invalidMethodConfigUrl) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling PUT on the /workspaces/*/*/method_configs/*/* path with a valid method configuration" - {
      "OK response is returned" in {
        Put(validMethodConfigUrl, MockWorkspaceServer.mockMethodConfigs.head) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling PUT on the /workspaces/*/*/method_configs/*/* path with an invalid method configuration" - {
      "Not Found response is returned" in {
        Put(validMethodConfigUrl, MockWorkspaceServer.mockInvalidWorkspace) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling PUT on an invalid /workspaces/*/*/method_configs/*/* path with a valid method configuration" - {
      "Not Found response is returned" in {
        Put(invalidMethodConfigUrl, MockWorkspaceServer.mockMethodConfigs.head) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/method_configs/*/* path" - {
      "OK response is returned" in {
        Get(validMethodConfigUrl) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/method_configs/*/*/validate path" - {
      "OK response is returned" in {
        Get(validMethodConfigUrl + "/validate") ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when calling GET on an invalid /workspaces/*/*/method_configs/*/* path" - {
      "Not Found response is returned" in {
        Get(invalidMethodConfigUrl) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/method_configs/*/* path" - {
      "MethodNotAllowed error is returned" in {
        Post(validMethodConfigUrl, MockWorkspaceServer.mockMethodConfigs.head) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          errorReportCheck("FireCloud", MethodNotAllowed)
        }
      }
    }

    "when calling PUT on the /workspaces/*/*/method_configs/*/* path without a valid authentication token" - {
      "Unauthorized response is returned" in {
        Put(validMethodConfigUrl, MockWorkspaceServer.mockMethodConfigs.head) ~> sealRoute(routes) ~> check {
          status should equal(Unauthorized)
          errorReportCheck("Rawls", Unauthorized)
        }
      }
    }

    /**
     * This test will fail if used as an integration test. Integration testing requires an existing
     * configuration in Agora that is accessible to the current user and a valid workspace in Rawls.
     */
    "when calling POST on the /workspaces/*/*/method_configs/copyFromMethodRepo path with valid workspace and configuration data" - {
      "Created response is returned" in {
        Post(validCopyFromRepoUrl, validConfigurationCopyFormData) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(Created)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/method_configs/copyFromMethodRepo path with invalid data" - {
      "BadRequest response is returned" in {
        Post(validCopyFromRepoUrl, invalidConfigurationCopyFormData) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should equal(BadRequest)
          errorReportCheck("Rawls", BadRequest)
        }
      }
    }

    "when calling POST on the /workspaces/*/*/method_configs/copyFromMethodRepo path without a valid authentication token" - {
      "Unauthorized response is returned" in {
        Post(validCopyFromRepoUrl, validConfigurationCopyFormData) ~> sealRoute(routes) ~> check {
          status should equal(Unauthorized)
          errorReportCheck("Rawls", Unauthorized)
        }
      }
    }

    "when calling GET on the /workspaces/*/*/method_configs/copyFromMethodRepo path" - {
      "MethodNotAllowed error is returned" in {
        Get(validCopyFromRepoUrl) ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          errorReportCheck("FireCloud", MethodNotAllowed)
        }
      }
    }

    "when calling PUT on the /workspaces/*/*/method_configs/copyFromMethodRepo path" - {
      "MethodNotAllowed error is returned" in {
        Put(validCopyFromRepoUrl) ~> sealRoute(routes) ~> check {
          status should equal(MethodNotAllowed)
          errorReportCheck("FireCloud", MethodNotAllowed)
        }
      }
    }

  }

}
