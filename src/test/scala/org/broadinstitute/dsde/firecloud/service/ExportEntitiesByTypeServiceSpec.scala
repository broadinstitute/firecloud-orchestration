package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.broadinstitute.dsde.firecloud.dataaccess.MockRawlsDAO
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.ExportEntitiesApiService
import spray.http.{HttpHeaders, HttpMethods, MessageChunk, Uri}
import spray.http.StatusCodes._

import scala.concurrent.duration._


class ExportEntitiesByTypeServiceSpec extends BaseServiceSpec with ExportEntitiesApiService {

  // On travis, slow processing causes the route to timeout and complete too quickly for the large content checks.
  override implicit val routeTestTimeout = RouteTestTimeout(30.seconds)

  def actorRefFactory: ActorSystem = system

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app, ActorMaterializer())

  val largeFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/large/entities/sample/tsv"
  val validFireCloudEntitiesSampleSetTSVPath = "/api/workspaces/broad-dsde-dev/valid/entities/sample_set/tsv"
  val validFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"
  val invalidFireCloudEntitiesParticipantSetTSVPath = "/api/workspaces/broad-dsde-dev/invalid/entities/participant_set/tsv"

  "ExportEntitiesApiService-ExportEntitiesByType" - {

    "when calling GET on exporting a valid entity type with filtered attributes" - {
      "OK response is returned and attributes are filtered" in {
        // Pick the first few headers from the list of available sample headers:
        val filterProps = MockRawlsDAO.largeSampleHeaders.take(5).map(_.name)
        // Grab the rest so we can double check the returned content to make sure they aren't returned.
        val missingProps = MockRawlsDAO.largeSampleHeaders.drop(5).map(_.name)
        val uri = Uri(largeFireCloudEntitiesSampleTSVPath).withQuery(("attributeNames", filterProps.mkString(",")))

        Get(uri) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          entity shouldNot be(empty) // Entity is the first line of content as output by StreamingActor
          chunks shouldNot be(empty) // Chunks has all of the rest of the content, as output by StreamingActor
          headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample.txt"))) should be(true)
          validateLineCount(chunks, MockRawlsDAO.largeSampleSize)
          val entityHeaderString = entity.asString
          filterProps.map { h => entityHeaderString.contains(h) should be(true) }
          missingProps.map { h => entityHeaderString.contains(h) should be(false) }
        }
      }
    }


    "when calling GET on exporting LARGE (20K) sample set" - {
      "OK response is returned" in {
        Get(largeFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          entity shouldNot be(empty) // Entity is the first line of content as output by StreamingActor
          chunks shouldNot be(empty) // Chunks has all of the rest of the content, as output by StreamingActor
          headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample.txt"))) should be(true)
          validateLineCount(chunks, MockRawlsDAO.largeSampleSize)
        }
      }
    }

    "when calling GET on exporting a valid collection type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleSetTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          entity shouldNot be(empty)
          headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample_set.zip"))) should be(true)
        }
      }
    }

    "when calling GET on exporting a valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          entity shouldNot be(empty)
          headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample.txt"))) should be(true)
        }
      }
    }

    "when calling GET on exporting an invalid collection type" - {
      "NotFound response is returned" in {
        Get(invalidFireCloudEntitiesParticipantSetTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
        }
      }
    }

    "when calling GET on exporting an invalid entity type" - {
      "NotFound response is returned" in {
        Get(invalidFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

    "when calling POST, PUT, PATCH, DELETE on export path" - {
      "MethodNotAllowed response is returned" in {
        List(HttpMethods.POST, HttpMethods.PUT, HttpMethods.DELETE, HttpMethods.PATCH) map { method =>
          new RequestBuilder(method)(invalidFireCloudEntitiesParticipantSetTSVPath) ~> sealRoute(exportEntitiesRoutes) ~> check {
            handled should be(true)
            status shouldNot equal(MethodNotAllowed)
          }
        }
      }
    }

  }

  private def validateLineCount(chunks: List[MessageChunk], count: Int): Unit = {
    val lineCount = chunks.map(c => scala.io.Source.fromString(c.data.asString).getLines().size).sum
    lineCount should equal(count)
  }

}
