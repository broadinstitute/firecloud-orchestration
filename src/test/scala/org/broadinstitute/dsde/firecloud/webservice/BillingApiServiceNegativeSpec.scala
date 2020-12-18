package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, POST, PUT}
import org.broadinstitute.dsde.firecloud.service.ServiceSpec
import org.mockserver.integration.ClientAndServer

import scala.concurrent.ExecutionContext

/**
  * We don't create a mock server so we can differentiate between methods that get passed through (and result in
  * InternalServerError) and those that aren't passed through at the first place (i.e. not 'handled')
  */
final class BillingApiServiceNegativeSpec extends ServiceSpec with BillingApiService {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  var workspaceServer: ClientAndServer = _

  "BillingApiService" - {
    "non-POST requests hitting /api/billing are not passed through" in {
      allHttpMethodsExcept(POST) foreach { method =>
        checkIfPassedThrough(billingServiceRoutes, method, "/billing", toBeHandled = false)
      }
    }

    "non-GET requests hitting /api/billing/{projectId}/members are not passed through" in {
      allHttpMethodsExcept(GET) foreach { method =>
        checkIfPassedThrough(billingServiceRoutes, method, "/billing/project1/members", toBeHandled = false)
      }
    }

    "non-DELETE/PUT requests hitting /api/billing/{projectId}/{role}/{email} are not passed through" in {
      allHttpMethodsExcept(DELETE, PUT) foreach { method =>
        checkIfPassedThrough(billingServiceRoutes, method, "/billing/project2/user/foo@bar.com", toBeHandled = false)
      }
    }
  }
}
