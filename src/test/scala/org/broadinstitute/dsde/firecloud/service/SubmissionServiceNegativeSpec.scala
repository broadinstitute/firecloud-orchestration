package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, POST}

import scala.concurrent.ExecutionContext

/**
  * We don't create a mock server so we can differentiate between methods that get passed through (and result in
  * InternalServerError) and those that aren't passed through at the first place (i.e. not 'handled')
  */
final class SubmissionServiceNegativeSpec extends ServiceSpec with SubmissionService {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val localSubmissionsCountPath = FireCloudConfig.Rawls.submissionsCountPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name)

  val localSubmissionsPath = FireCloudConfig.Rawls.submissionsPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name)

  val localSubmissionIdPath = FireCloudConfig.Rawls.submissionsIdPath.format(
    MockWorkspaceServer.mockValidWorkspace.namespace,
    MockWorkspaceServer.mockValidWorkspace.name,
    MockWorkspaceServer.mockValidId)

  "SubmissionService" - {
    "non-GET requests hitting the /submissions/queueStatus path are not passed through" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(submissionServiceRoutes, method, "/submissions/queueStatus", toBeHandled = false)
      }
    }

    "non-GET requests hitting the /workspaces/*/*/submissionsCount path are not passed through" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(submissionServiceRoutes, method, localSubmissionsCountPath, toBeHandled = false)
      }
    }

    "non-GET/POST requests hitting the /workspaces/*/*/submissions path are not passed through" in {
      allHttpMethodsExcept(GET, POST) foreach { method =>
        checkIfPassedThrough(submissionServiceRoutes, method, localSubmissionsPath, toBeHandled = false)
      }
    }

    "non-POST requests hitting the /workspaces/*/*/submissions/validate path are not passed through" in {
      // Also excluding DELETE and GET since the path matches
      // /workspaces/*/*/submissions/{submissionId} APIs as well
      allHttpMethodsExcept(DELETE, GET, POST) foreach { method =>
        checkIfPassedThrough(submissionServiceRoutes, method, s"$localSubmissionsPath/validate", toBeHandled = false)
      }
    }

    "non-DELETE/GET requests hitting the /workspaces/*/*/submissions/* path are not passed through" in {
      allHttpMethodsExcept(DELETE, GET) foreach { method =>
        checkIfPassedThrough(submissionServiceRoutes, method, localSubmissionIdPath, toBeHandled = false)
      }
    }

    "non-GET requests hitting the /workspaces/*/*/submissions/*/workflows/workflowId path are not passed through" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(submissionServiceRoutes, method, s"$localSubmissionIdPath/workflows/workflowId", toBeHandled = false)
      }
    }

    "non-GET requests hitting the /workspaces/*/*/submissions/*/workflows/workflowId/outputs path are not passed through" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(submissionServiceRoutes, method, s"$localSubmissionIdPath/workflows/workflowId/outputs", toBeHandled = false)
      }
    }
  }

}
