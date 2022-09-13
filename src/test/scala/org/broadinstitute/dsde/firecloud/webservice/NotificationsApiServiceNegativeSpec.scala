package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods.GET
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.service.ServiceSpec

import scala.concurrent.ExecutionContext

/**
  * We don't create a mock server so we can differentiate between methods that get passed through (and result in
  * InternalServerError) and those that aren't passed through at the first place (i.e. not 'handled')
  */
final class NotificationsApiServiceNegativeSpec extends ServiceSpec with NotificationsApiService {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  "NotificationsApiService" - {
    val namespace = MockWorkspaceServer.mockValidWorkspace.namespace
    val name = MockWorkspaceServer.mockValidWorkspace.name
    val workspaceNotificationUri = s"/api/notifications/workspace/$namespace/$name"

    val generalNotificationUri = "/api/notifications/general"

    // N.B. this file used to contain positive passthrough tests that checked to see if the request was handled
    // by the route. However, those tests are unstable without a mockserver behind the passthrough or other way
    // of actually handling the test; Akka shuts down its connection pool when it sees that requests are not connecting
    // to the target. I have removed these positive tests, since we have coverage for them anyway in NotificationsApisServiceSpec.

    "non-GET requests should not be passed through for general notifications" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(notificationsRoutes, method, generalNotificationUri, toBeHandled = false)
      }
    }

    "non-GET requests should not be passed through for workspace notifications" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(notificationsRoutes, method, workspaceNotificationUri, toBeHandled = false)
      }
    }
  }

}
