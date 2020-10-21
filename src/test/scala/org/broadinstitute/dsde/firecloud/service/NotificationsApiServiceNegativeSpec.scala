package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.webservice.NotificationsApiService
import akka.http.scaladsl.model.HttpMethods.GET

/**
  * We don't create a mock server so we can differentiate between methods that get passed through (and result in
  * InternalServerError) and those that aren't passed through at the first place (i.e. not 'handled')
  */
final class NotificationsApiServiceNegativeSpec extends BaseServiceSpec with NotificationsApiService {

  "NotificationsApiService" - {
    val namespace = MockWorkspaceServer.mockValidWorkspace.namespace
    val name = MockWorkspaceServer.mockValidWorkspace.name
    val workspaceNotificationUri = s"/api/notifications/workspace/$namespace/$name"

    val generalNotificationUri = "/api/notifications/general"

    "GET requests with valid URIs should be passed through (and hit 500 due to the absence of a server)" in {
      checkIfPassedThrough(notificationsRoutes, GET, generalNotificationUri, toBeHandled = true)
      checkIfPassedThrough(notificationsRoutes, GET, workspaceNotificationUri, toBeHandled = true)
    }

    "non-GET requests should not be passed through" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(notificationsRoutes, method, generalNotificationUri, toBeHandled = false)
        checkIfPassedThrough(notificationsRoutes, method, workspaceNotificationUri, toBeHandled = false)
      }
    }
  }

}
