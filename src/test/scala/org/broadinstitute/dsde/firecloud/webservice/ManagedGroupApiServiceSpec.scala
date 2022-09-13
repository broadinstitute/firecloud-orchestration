package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.model.StatusCodes.{Created, NoContent, OK}
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, ManagedGroupService}

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 4/2/18.
  */
class ManagedGroupApiServiceSpec extends BaseServiceSpec with ManagedGroupApiService {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

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
