package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model._
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer._
import org.mockserver.model.HttpRequest._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.webservice.EntityApiService

class EntitiesWithTypeServiceSpec extends BaseServiceSpec with EntityApiService with SprayJsonSupport {

  def actorRefFactory = system

  // Due to the large volume of service specific test cases, generate them here to prevent the
  // extra clutter
  var workspaceServer: ClientAndServer = _
  val validFireCloudPath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath + "/broad-dsde-dev/valid/"
  val invalidFireCloudPath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath + "/broad-dsde-dev/invalid/"
  val sampleAtts = Map(
    AttributeName.withDefaultNS("sample_type") -> AttributeString("Blood"),
    AttributeName.withDefaultNS("ref_fasta") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta"),
    AttributeName.withDefaultNS("ref_dict") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.dict"),
    AttributeName.withDefaultNS("participant_id") -> AttributeEntityReference("participant", "subject_HCC1143")
  )
  val validSampleEntities = List(Entity("sample_01", "sample", sampleAtts))
  val participantAtts = Map(
    AttributeName.withDefaultNS("tumor_platform") -> AttributeString("illumina"),
    AttributeName.withDefaultNS("ref_fasta") -> AttributeString("gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta"),
    AttributeName.withDefaultNS("tumor_strip_unpaired") -> AttributeString("TRUE")
  )
  val validParticipants = List(Entity("subject_HCC1143", "participant", participantAtts))

  override def beforeAll(): Unit = {

    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)

    // Valid cases
    workspaceServer
      .when(
        request().withMethod("GET").withPath(validFireCloudPath + "entities").withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody(Map("participant"->1, "sample"->1).toJson.compactPrint).withStatusCode(OK.intValue)
      )
    workspaceServer
      .when(
        request().withMethod("GET").withPath(validFireCloudPath + "entities/sample").withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody(validSampleEntities.toJson.compactPrint).withStatusCode(OK.intValue)
      )
    workspaceServer
      .when(
        request().withMethod("GET").withPath(validFireCloudPath + "entities/participant").withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody(validParticipants.toJson.compactPrint).withStatusCode(OK.intValue)
      )

    // Invalid cases:
    workspaceServer
      .when(
        request().withMethod("GET").withPath(invalidFireCloudPath + "entities").withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody(List("participant", "sample").toJson.compactPrint).withStatusCode(OK.intValue)
      )
    workspaceServer
      .when(
        request().withMethod("GET").withPath(invalidFireCloudPath + "entities/sample").withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody("Error").withStatusCode(InternalServerError.intValue)
      )
    workspaceServer
      .when(
        request().withMethod("GET").withPath(invalidFireCloudPath + "entities/participant").withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody("Error").withStatusCode(InternalServerError.intValue)
      )

  }

  override def afterAll(): Unit = {
    workspaceServer.stop()
  }

  "EntityService-EntitiesWithType" - {

    "when calling GET on a valid entities_with_type path" - {
      "valid list of entity types are returned" in {
        val path = validFireCloudPath + "entities_with_type"
        Get(path) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(OK)
          val entities = responseAs[List[Entity]]
          entities shouldNot be(empty)
        }
      }
    }

    "when calling GET on an invalid entities_with_type path" - {
      "server error is returned" in {
        val path = invalidFireCloudPath + "entities_with_type"
        Get(path) ~> dummyUserIdHeaders("1234") ~> sealRoute(entityRoutes) ~> check {
          status should be(InternalServerError)
          errorReportCheck("FireCloud", InternalServerError)
        }
      }
    }

  }

}
