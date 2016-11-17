package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import spray.http.StatusCodes._
import spray.json.DefaultJsonProtocol._
import spray.json._

class ExportEntitiesByTypeServiceSpec extends BaseServiceSpec with EntityService {

  def actorRefFactory = system

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app)

  var workspaceServer: ClientAndServer = _
  val validFireCloudEntitiesSampleTSVPath = "/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"

  val sampleAtts = {
    Map(
      AttributeName.withDefaultNS("sample_type") -> AttributeString("Blood"),
      AttributeName.withDefaultNS("header_1") -> AttributeString(MockUtils.randomAlpha()),
      AttributeName.withDefaultNS("header_2") -> AttributeString(MockUtils.randomAlpha()),
      AttributeName.withDefaultNS("participant_id") -> AttributeEntityReference("participant", "participant_name")
    )
  }

  val validSampleEntities = List(
    RawlsEntity("sample_01", "sample", sampleAtts),
    RawlsEntity("sample_02", "sample", sampleAtts),
    RawlsEntity("sample_03", "sample", sampleAtts),
    RawlsEntity("sample_04", "sample", sampleAtts)
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
        Get(validFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(OK)
          response.entity shouldNot be(empty)
        }
      }
    }

    "when calling GET on exporting an invalid entity type" - {
      "NotFound response is returned" in {
        Get(invalidFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(NotFound)
          errorReportCheck("FireCloud", NotFound)
        }
      }
    }

  }

}
