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

import scala.io.Source

class ExportEntitiesByTypeServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest
with Matchers with EntityService with FireCloudRequestBuilding {

  def actorRefFactory = system

  var workspaceServer: ClientAndServer = _
  val validFireCloudEntitiesSampleTSVPath = "/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"

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

  "EntityMatrix" - {
    "should generate a TSV string with valid data" - {
      val tsv = EntityMatrix.makeTsvString(validSampleEntities, "sample")
      tsv shouldNot be(empty)
      // Resultant data should have a header and one line per sample entity:
      Source.fromString(tsv).getLines().size should equal(validSampleEntities.size + 1)
    }
  }

  "EntityService-ExportEntitiesByType" - {

    "when calling GET on exporting a valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleTSVPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(OK)
          response.entity shouldNot be(empty)
        }
      }
    }

    "when calling GET on exporting an invalid entity type" - {
      "OK response is returned" in {
        Get(invalidFireCloudEntitiesSampleTSVPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
        }
      }
    }

  }

}
