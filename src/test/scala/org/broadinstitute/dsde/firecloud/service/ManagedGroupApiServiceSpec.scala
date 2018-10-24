package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.mock.MockUtils.samServerPort
import org.broadinstitute.dsde.firecloud.model.{ManagedGroupRoles, WithAccessToken}
import org.broadinstitute.dsde.firecloud.webservice.ManagedGroupApiService
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.mockserver.model.HttpRequest.request
import spray.http.HttpMethods
import spray.http.StatusCodes.{Created, NoContent, OK}

/**
  * Created by mbemis on 4/2/18.
  */
class ManagedGroupApiServiceSpec extends BaseServiceSpec with ManagedGroupApiService {

  def actorRefFactory = system

  val managedGroupServiceConstructor:(WithAccessToken) => ManagedGroupService = ManagedGroupService.constructor(app)

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