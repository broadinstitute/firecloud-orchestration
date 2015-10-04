package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.{MockUtils, MockWorkspaceServer}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
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

class EntitiesWithTypeServiceSpec extends FreeSpec with ScalaFutures with ScalatestRouteTest
with Matchers with EntityService with FireCloudRequestBuilding {

  def actorRefFactory = system

  // Due to the large volume of service specific test cases, generate them here to prevent the
  // extra clutter
  var workspaceServer: ClientAndServer = _
  val validFireCloudPath = "/workspaces/broad-dsde-dev/valid/"
  val invalidFireCloudPath = "/workspaces/broad-dsde-dev/invalid/"
  val sampleAtts = Map(
    "sample_type" -> "Blood".toJson,
    "ref_fasta" -> "gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta".toJson,
    "ref_dict" -> "gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.dict".toJson,
    "participant_id" -> """{"entityType":"participant","entityName":"subject_HCC1143"}""".toJson
  )
  val validSampleEntities = List(EntityWithType("sample_01", "sample", Some(sampleAtts)))
  val participantAtts = Map(
    "tumor_platform" -> "illumina".toJson,
    "ref_fasta" -> "gs://cancer-exome-pipeline-demo-data/Homo_sapiens_assembly19.fasta".toJson,
    "tumor_strip_unpaired" -> "TRUE".toJson
  )
  val validParticipants = List(EntityWithType("subject_HCC1143", "participant", Some(participantAtts)))

  override def beforeAll(): Unit = {

    workspaceServer = startClientAndServer(MockWorkspaceServer.workspaceServerPort)

    // Valid cases
    workspaceServer
      .when(
        request().withMethod("GET").withPath(validFireCloudPath + "entities").withHeader(MockUtils.authHeader))
      .respond(
        org.mockserver.model.HttpResponse.response()
          .withHeaders(MockUtils.header).withBody(List("participant", "sample").toJson.compactPrint).withStatusCode(OK.intValue)
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
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(OK)
          val entities = responseAs[List[EntityWithType]]
          entities shouldNot be(empty)
        }
      }
    }

    "when calling GET on an invalid entities_with_type path" - {
      "valid list of entity types are returned" in {
        val path = invalidFireCloudPath + "entities_with_type"
        Get(path) ~> dummyAuthHeaders ~> sealRoute(routes) ~> check {
          status should be(InternalServerError)
        }
      }
    }

  }

}
