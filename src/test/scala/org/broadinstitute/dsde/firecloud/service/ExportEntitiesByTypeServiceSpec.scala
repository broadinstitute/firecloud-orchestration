package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._

class ExportEntitiesByTypeServiceSpec extends ServiceSpec with EntityService {

  def actorRefFactory = system

  var workspaceServer: ClientAndServer = _
  val validFireCloudEntitiesSampleTSVPath = "/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"

  val sampleAtts = {
    Map(
      "sample_type" -> "Blood".toJson,
      "header_1" -> MockUtils.randomAlpha().toJson,
      "header_2" -> MockUtils.randomAlpha().toJson,
      "participant_id" -> """{"entityType":"participant","entityName":"participant_name"}""".parseJson
    )
  }

  val validSampleEntities = List(
    EntityWithType("sample_01", "sample", Some(sampleAtts)),
    EntityWithType("sample_02", "sample", Some(sampleAtts)),
    EntityWithType("sample_03", "sample", Some(sampleAtts)),
    EntityWithType("sample_04", "sample", Some(sampleAtts))
  )

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)
    // Valid Entities by sample type case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid") + "/sample")
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withBody(validSampleEntities.toJson.compactPrint)
          .withStatusCode(OK.intValue)
      )

    // Invalid Entities by sample type case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "invalid") + "/sample")
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
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
      "NotFound response is returned" in {
        Get(invalidFireCloudEntitiesSampleTSVPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

  }

}
