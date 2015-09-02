package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{Matchers, FreeSpec}
import org.scalatest.concurrent.ScalaFutures
import spray.http.StatusCodes._
import spray.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

class EntityServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest
  with Matchers with EntityService with FireCloudRequestBuilding {

  def actorRefFactory = system

  override def beforeAll(): Unit = {
    MockWorkspaceServer.startWorkspaceServer()
  }

  override def afterAll(): Unit = {
    MockWorkspaceServer.stopWorkspaceServer()
  }

  "EntityService" - {

    "when calling GET on entities_with_type path with a valid workspace" - {
      "valid list of entity types are returned" in {
        val path = s"${MockWorkspaceServer.entitiesWithTypeBasePath}entities_with_type"
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(OK)
          val entities = responseAs[List[EntityWithType]]
          entities shouldNot be(empty)
        }
      }
    }
  }

}
