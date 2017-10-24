package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import spray.http.HttpMethods.{GET, DELETE, POST}

/**
  * We don't create a mock server so we can differentiate between methods that get passed through (and result in
  * InternalServerError) and those that aren't passed through at the first place (i.e. not 'handled')
  */
final class SubmissionServiceNegativeSpec extends ServiceSpec with SubmissionService {

  def actorRefFactory = system

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
        checkIfPassedThrough(routes, method, "/submissions/queueStatus", toBeHandled = false)
      }
    }

    "non-GET/POST requests hitting the /workspaces/*/*/submissions path are not passed through" in {
      allHttpMethodsExcept(GET, POST) foreach { method =>
        checkIfPassedThrough(routes, method, localSubmissionsPath, toBeHandled = false)
      }
    }

//    "non-POST requests hitting the /workspaces/*/*/submissions/validate path are not passed through path are not passed through" in {
//      allHttpMethodsExcept(POST) foreach { method =>
//        checkIfPassedThrough(routes, method, s"$localSubmissionsPath/validate", toBeHandled = false)
//      }
//    }

    "non-DELETE/GET requests hitting the /workspaces/*/*/submissions/* path are not passed through" in {
      allHttpMethodsExcept(DELETE, GET) foreach { method =>
        checkIfPassedThrough(routes, method, localSubmissionIdPath, toBeHandled = false)
      }
    }
  }

}
