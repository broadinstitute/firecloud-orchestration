package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{EntityCopyDefinition, ModelJsonProtocol, WorkspaceName}
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers}
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.testkit.ScalatestRouteTest

class EntityServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest with Matchers
  with EntityService with FireCloudRequestBuilding {

  def actorRefFactory = system

  var workspaceServer: ClientAndServer = _
  val validFireCloudEntitiesPath = "/workspaces/broad-dsde-dev/valid/entities"
  val validFireCloudEntitiesSamplePath = "/workspaces/broad-dsde-dev/valid/entities/sample"
  val validFireCloudEntitiesCopyPath = "/workspaces/broad-dsde-dev/valid/entities/copy"
  val invalidFireCloudEntitiesPath = "/workspaces/broad-dsde-dev/invalid/entities"
  val entityCopy = EntityCopyDefinition(
    sourceWorkspace = WorkspaceName(namespace=Some("broad-dsde-dev"), name=Some("other-ws")),
    entityType = "sample", Seq("sample_01"))
  val sampleAtts = Map(
    "sample_type" -> "Blood".toJson,
    "ref_fasta" -> "gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta".toJson,
    "ref_dict" -> "gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.dict".toJson,
    "participant_id" -> """{"entityType":"participant","entityName":"subject_HCC1143"}""".toJson
  )
  val validSampleEntities = List(EntityWithType("sample_01", "sample", Some(sampleAtts)))

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
    // Valid entities case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid"))
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // Valid Copy case
    workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(FireCloudConfig.Rawls.workspacesEntitiesCopyPath)
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(Created.intValue)
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "EntityService" - {

    "when calling GET on valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSamplePath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(OK)
          response.entity shouldNot be(empty)
        }
      }
    }

    "when calling GET on valid entities" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(OK)
        }
      }
    }

    "when calling GET on entities in an unknown workspace" - {
      "Not Found response is returned" in {
        Get(invalidFireCloudEntitiesPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
        }
      }
    }

    "when calling POST on valid copy entities" - {
      "Created response is returned" in {
        Post(validFireCloudEntitiesCopyPath, entityCopy) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(Created)
        }
      }
    }

  }

}
