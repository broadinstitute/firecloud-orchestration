package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.rawls.model._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpClassCallback.callback
import org.mockserver.model.HttpRequest._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext

class EntityApiServiceSpec extends BaseServiceSpec with EntityApiService with SprayJsonSupport {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)

  var workspaceServer: ClientAndServer = _
  val apiPrefix = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  val validFireCloudEntitiesPath = apiPrefix + "/broad-dsde-dev/valid/entities"
  val validFireCloudEntitiesSamplePath = apiPrefix + "/broad-dsde-dev/valid/entities/sample"
  val validFireCloudEntityQuerySamplePath = apiPrefix + "/broad-dsde-dev/valid/entityQuery/sample"
  val validFireCloudEntitiesCopyPath = apiPrefix + "/broad-dsde-dev/valid/entities/copy"
  val validFireCloudEntitiesBulkDeletePath = apiPrefix + "/broad-dsde-dev/valid/entities/delete"
  val invalidFireCloudEntitiesPath = apiPrefix + "/broad-dsde-dev/invalid/entities"
  val invalidFireCloudEntitiesSamplePath = apiPrefix + "/broad-dsde-dev/invalid/entities/sample"
  val invalidFireCloudEntitiesCopyPath = apiPrefix + "/broad-dsde-dev/invalid/entities/copy"

  val validEntityCopy = EntityCopyWithoutDestinationDefinition(
    sourceWorkspace = WorkspaceName(namespace="broad-dsde-dev", name="other-ws"),
    entityType = "sample", Seq("sample_01"))
  val invalidEntityCopy = EntityCopyWithoutDestinationDefinition(
    sourceWorkspace = WorkspaceName(namespace="invalid", name="other-ws"),
    entityType = "sample", Seq("sample_01"))

  val validEntityDelete = Seq(EntityId("sample","id"),EntityId("sample","bar"))
  val invalidEntityDelete = validEntityCopy // we're testing that the payload can't be unmarshalled to a Seq[EntityId]
  val mixedFailEntityDelete = Seq(EntityId("sample","foo"),EntityId("failme","kthxbai"),EntityId("sample","bar"))
  val allFailEntityDelete = Seq(EntityId("failme","kthxbai"))

  def entityCopyWithDestination(copyDef: EntityCopyDefinition) = new EntityCopyDefinition(
    sourceWorkspace = copyDef.sourceWorkspace,
    destinationWorkspace = WorkspaceName("broad-dsde-dev", "valid"),
    entityType = copyDef.entityType,
    entityNames = copyDef.entityNames)

  val sampleAtts = Map(
    AttributeName.withDefaultNS("sample_type") -> AttributeString("Blood"),
    AttributeName.withDefaultNS("ref_fasta") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta"),
    AttributeName.withDefaultNS("ref_dict") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.dict"),
    AttributeName.withDefaultNS("participant_id") -> AttributeEntityReference("participant", "subject_HCC1143")
  )
  val validSampleEntities = List(Entity("sample_01", "sample", sampleAtts))

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)
    // Valid Entities by sample type case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid") + "/sample"))
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
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // Valid entity query case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entityQueryPath.format("broad-dsde-dev", "valid") + "/sample"))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withStatusCode(OK.intValue)
      )
    // Valid/Invalid Copy cases
    workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesEntitiesCopyPath))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidEntityCopyCallback")
      )

    // Invalid Entities by sample type case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "invalid") + "/sample"))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
      )
    // Invalid entities case
    workspaceServer
      .when(
        request()
          .withMethod("GET")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "invalid")))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header)
          .withStatusCode(NotFound.intValue)
          .withBody(MockUtils.rawlsErrorReport(NotFound).toJson.compactPrint)
      )
    // Bulk delete endpoint
    workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.entitiesPath.format("broad-dsde-dev", "valid") + "/delete"))
      .callback(
        callback().
          withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidEntityDeleteCallback")
      )
  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "EntityService" - {

    "when calling GET on valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSamplePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(OK)
        }
      }
    }

    "when calling GET on valid entities" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(OK)
        }
      }
    }

    "when calling GET on valid entityQuery with no params" - {
      "OK response is returned" in {
        Get(validFireCloudEntityQuerySamplePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(OK)
        }
      }
    }

    "when calling GET on valid entityQuery with params" - {
      "OK response is returned" in {
        Get(validFireCloudEntityQuerySamplePath + "?page=1&pageSize=1") ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(OK)
        }
      }
    }

    "when calling POST on valid copy entities" - {
      "Created response is returned" in {
        Post(validFireCloudEntitiesCopyPath, validEntityCopy) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(Created)
        }
      }
    }

    "when calling POST on invalid copy entities" - {
      "NotFound response is returned" in {
        Post(validFireCloudEntitiesCopyPath, invalidEntityCopy) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling GET on an entity type in an unknown workspace" - {
      "NotFound response is returned with an ErrorReport" in {
        Get(invalidFireCloudEntitiesSamplePath) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling GET on entities in an unknown workspace" - {
      "NotFound response is returned with an ErrorReport" in {
        Get(invalidFireCloudEntitiesPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling POST on copy entities in an unknown workspace" - {
      "NotFound response is returned with an ErrorReport" in {
        Post(invalidFireCloudEntitiesCopyPath, validEntityCopy) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling bulk entity delete with a valid payload" - {
      "response is NoContent" in {
        Post(validFireCloudEntitiesBulkDeletePath, validEntityDelete) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(NoContent)
        }
      }
    }

    "when calling bulk entity delete with an invalid payload" - {
      "BadRequest is returned" in {
        Post(validFireCloudEntitiesBulkDeletePath, invalidEntityDelete) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(BadRequest)
        }
      }
    }

    "when calling bulk entity delete with some missing entities" - {
      "BadRequest is returned with an ErrorReport" in {
        Post(validFireCloudEntitiesBulkDeletePath, mixedFailEntityDelete) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(BadRequest)
          errorReportCheck("Rawls", BadRequest)
        }
      }
    }

    "when calling bulk entity delete with all missing entities" - {
      "BadRequest is returned with an ErrorReport" in {
        Post(validFireCloudEntitiesBulkDeletePath, allFailEntityDelete) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(BadRequest)
          errorReportCheck("Rawls", BadRequest)
        }
      }
    }
  }

}
