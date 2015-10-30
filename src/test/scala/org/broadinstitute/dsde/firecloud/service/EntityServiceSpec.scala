package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpCallback._
import org.mockserver.model.HttpRequest._
import spray.http.StatusCodes._
import spray.json._

import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

class EntityServiceSpec extends ServiceSpec with EntityService {

  def actorRefFactory = system

  var workspaceServer: ClientAndServer = _
  val validFireCloudEntitiesPath = "/workspaces/broad-dsde-dev/valid/entities"
  val validFireCloudEntitiesSamplePath = "/workspaces/broad-dsde-dev/valid/entities/sample"
  val validFireCloudEntitiesCopyPath = "/workspaces/broad-dsde-dev/valid/entities/copy"
  val validFireCloudEntitiesBulkDeletePath = "/workspaces/broad-dsde-dev/valid/entities/delete"
  val invalidFireCloudEntitiesPath = "/workspaces/broad-dsde-dev/invalid/entities"
  val invalidFireCloudEntitiesSamplePath = "/workspaces/broad-dsde-dev/invalid/entities/sample"
  val invalidFireCloudEntitiesCopyPath = "/workspaces/broad-dsde-dev/invalid/entities/copy"

  val validEntityCopy = EntityCopyDefinition(
    sourceWorkspace = WorkspaceName(namespace=Some("broad-dsde-dev"), name=Some("other-ws")),
    entityType = "sample", Seq("sample_01"))
  val invalidEntityCopy = EntityCopyDefinition(
    sourceWorkspace = WorkspaceName(namespace=Some("invalid"), name=Some("other-ws")),
    entityType = "sample", Seq("sample_01"))

  val validEntityDelete = EntityDeleteDefinition(false, Seq(EntityId("sample","id"),EntityId("sample","bar")))
  val invalidEntityDelete = validEntityCopy // we're testing that the payload can't be unmarshalled to an EntityDeleteDefinition
  val mixedFailEntityDelete = EntityDeleteDefinition(false, Seq(EntityId("sample","foo"),EntityId("failme","kthxbai"),EntityId("sample","bar")))

  def entityCopyWithDestination(copyDef: EntityCopyDefinition) = new EntityCopyWithDestinationDefinition(
    sourceWorkspace = copyDef.sourceWorkspace,
    destinationWorkspace = WorkspaceName(Some("broad-dsde-dev"), Some("valid")),
    entityType = copyDef.entityType,
    entityNames = copyDef.entityNames)

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
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid") + "/sample")
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
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid"))
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // Valid/Invalid Copy cases
    workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesEntitiesCopyPath)
          .withHeader(MockUtils.authHeader))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidEntityCopyCallback")
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
          .withBody(MockWorkspaceServer.rawlsErrorReport(NotFound).toJson.compactPrint)
      )
    // Invalid entities case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "invalid"))
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockWorkspaceServer.rawlsErrorReport(NotFound).toJson.compactPrint)
      )
    // Bulk delete endpoint
    workspaceServer
      .when(
        request()
          .withMethod("DELETE")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid") + "/sample/.+")
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(OK.intValue)
      )
    workspaceServer
      .when(
        request()
          .withMethod("DELETE")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid") + "/failme/.+")
          .withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockWorkspaceServer.rawlsErrorReport(NotFound).toJson.compactPrint)
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

    "when calling POST on valid copy entities" - {
      "Created response is returned" in {
        Post(validFireCloudEntitiesCopyPath, validEntityCopy) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(Created)
        }
      }
    }

    "when calling POST on invalid copy entities" - {
      "NotFound response is returned" in {
        Post(validFireCloudEntitiesCopyPath, invalidEntityCopy) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling GET on an entity type in an unknown workspace" - {
      "NotFound response is returned with an ErrorReport" in {
        Get(invalidFireCloudEntitiesSamplePath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling GET on entities in an unknown workspace" - {
      "NotFound response is returned with an ErrorReport" in {
        Get(invalidFireCloudEntitiesPath) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling POST on copy entities in an unknown workspace" - {
      "NotFound response is returned with an ErrorReport" in {
        Post(invalidFireCloudEntitiesCopyPath, validEntityCopy) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling bulk entity delete" - {
      "response is OK" in {
        Post(validFireCloudEntitiesBulkDeletePath, validEntityDelete) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(OK)
        }
      }
    }

    "when calling bulk entity delete with an invalid payload" - {
      "BadRequest is returned" in {
        Post(validFireCloudEntitiesBulkDeletePath, invalidEntityDelete) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(BadRequest)
        }
      }
    }

    "when calling bulk entity delete and expecting mixed success/fail" - {
      "InternalServerError is returned with an ErrorReport" in {
        Post(validFireCloudEntitiesBulkDeletePath, mixedFailEntityDelete) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(InternalServerError)
          errorReportCheck("FireCloud", InternalServerError)
        }
      }
    }
  }

}
