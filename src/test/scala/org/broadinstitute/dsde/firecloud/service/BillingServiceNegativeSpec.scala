package org.broadinstitute.dsde.firecloud.service

import org.mockserver.integration.ClientAndServer
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, POST, PUT}

/**
  * We don't create a mock server so we can differentiate between methods that get passed through (and result in
  * InternalServerError) and those that aren't passed through at the first place (i.e. not 'handled')
  */
final class BillingServiceNegativeSpec extends ServiceSpec with BillingService {
  def actorRefFactory = system
  var workspaceServer: ClientAndServer = _

  "BillingService" - {
    "non-POST requests hitting /api/billing are not passed through" in {
      allHttpMethodsExcept(POST) foreach { method =>
        checkIfPassedThrough(routes, method, "/billing", toBeHandled = false)
      }
    }

    "non-GET requests hitting /api/billing/{projectId}/members are not passed through" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(routes, method, "/billing/project1/members", toBeHandled = false)
      }
    }

    "non-DELETE/PUT requests hitting /api/billing/{projectId}/{role}/{email} are not passed through" in {
      allHttpMethodsExcept(DELETE, PUT) foreach { method =>
        checkIfPassedThrough(routes, method, "/billing/project2/user/foo@bar.com", toBeHandled = false)
      }
    }
  }
}
