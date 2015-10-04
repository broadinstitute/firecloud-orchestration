package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.EntityMatrix
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers}
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.testkit.ScalatestRouteTest

class ExportEntitiesByTypeServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest
with Matchers with EntityService with FireCloudRequestBuilding {

  def actorRefFactory = system

  var workspaceServer: ClientAndServer = _
  val validFireCloudEntitiesSampleExportPath = "/workspaces/broad-dsde-dev/valid/entities/sample/export"
  val invalidFireCloudEntitiesSampleExportPath = "/workspaces/broad-dsde-dev/invalid/entities/sample/export"

  def sampleAtts(): Map[String, JsValue] = {
    Map(
      "sample_type" -> "Blood".toJson,
      "header_1" -> MockUtils.randomAlpha().toJson,
      "header_2" -> MockUtils.randomAlpha().toJson,
      "participant_id" -> """{"entityType":"participant","entityName":"participant_name"}""".parseJson
    )
  }

  val validSampleEntities = List(
    EntityWithType("sample_01", "sample", Some(sampleAtts())),
    EntityWithType("sample_02", "sample", Some(sampleAtts())),
    EntityWithType("sample_03", "sample", Some(sampleAtts())),
    EntityWithType("sample_04", "sample", Some(sampleAtts()))
  )

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockWorkspaceServer.workspaceServerPort)
    // Valid Entities by sample type case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid") + "/sample")
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withBody(validSampleEntities.toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "EntityValueMatrix" - {
    "should generate a matrix with valid data" - {
      val matrix = new EntityMatrix().initWithEntities(validSampleEntities, "sample")
      matrix.getHeaders shouldNot be(empty)
      matrix.getRows shouldNot be(empty)
      matrix.getRows.size should equal(validSampleEntities.size)
    }
  }

  "EntityService-ExportEntitiesByType" - {

    "when calling GET on exporting a valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleExportPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(OK)
          response.entity shouldNot be(empty)
        }
      }
    }

    "when calling GET on exporting an invalid entity type" - {
      "OK response is returned" in {
        Get(invalidFireCloudEntitiesSampleExportPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
        }
      }
    }

  }

}
