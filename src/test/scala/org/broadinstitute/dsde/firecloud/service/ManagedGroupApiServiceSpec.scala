package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.{MockGoogleServicesDAO, MockUtils}
import org.broadinstitute.dsde.firecloud.mock.MockUtils.samServerPort
import org.broadinstitute.dsde.firecloud.model.{ManagedGroupRoles, WithAccessToken}
import org.broadinstitute.dsde.firecloud.webservice.ManagedGroupApiService
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import akka.http.scaladsl.model.StatusCodes.{Created, NoContent, OK}
import org.broadinstitute.dsde.firecloud.dataaccess.{MockAgoraDAO, MockConsentDAO, MockLogitDAO, MockOntologyDAO, MockRawlsDAO, MockResearchPurposeSupport, MockSamDAO, MockSearchDAO, MockShareLogDAO, MockThurloeDAO}

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 4/2/18.
  */
class ManagedGroupApiServiceSpec extends BaseServiceSpec with ApiServiceSpec {

  def actorRefFactory = system

  case class TestApiService(agoraDao: MockAgoraDAO, googleDao: MockGoogleServicesDAO, ontologyDao: MockOntologyDAO, consentDao: MockConsentDAO, rawlsDao: MockRawlsDAO, samDao: MockSamDAO, searchDao: MockSearchDAO, researchPurposeSupport: MockResearchPurposeSupport, thurloeDao: MockThurloeDAO, logitDao: MockLogitDAO, shareLogDao: MockShareLogDAO)(implicit val executionContext: ExecutionContext) extends ApiServices

  val uniqueId = "normal-user"

  "ManagedGroupApiService" - {

    "when GET-ting my group membership" - {
      "OK response is returned" in {
        Get("/api/groups") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when POST-ing to create a group" - {
      "Created response is returned" in {
        Post("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(Created)
        }

        Post("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(Created)
        }
      }
    }

    "when GET-ting membership of a group" - {
      "OK response is returned" in {
        Post("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(Created)
        }

        Get("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(OK)
        }
      }
    }

    "when DELETE-ing to delete a group" - {
      "OK response is returned" in {
        Post("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(Created)
        }

        Delete("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(NoContent)
        }
      }
    }

    "when PUT-ting to add a member to a group" - {
      "OK response is returned" in {
        Post("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(Created)
        }

        Put("/api/groups/example-group/admin/test@test.test") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(NoContent)
        }
      }
    }

    "when DELETE-ing to remove a member from a group" - {
      "OK response is returned" in {
        Post("/api/groups/example-group") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(Created)
        }

        Delete("/api/groups/example-group/admin/test@test.test") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(NoContent)
        }
      }
    }

    "when POST-ing to request access to a group" - {
      "OK response is returned" in {
        Post("/api/groups/example-group/requestAccess") ~>
          dummyUserIdHeaders(uniqueId) ~> sealRoute(managedGroupServiceRoutes) ~> check {
          status should equal(NoContent)
        }
      }
    }
  }
}
