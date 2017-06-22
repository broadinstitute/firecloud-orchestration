package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.ExportEntitiesApiService
import spray.http.HttpHeaders
import spray.http.StatusCodes._

class ExportEntitiesByTypeServiceSpec extends BaseServiceSpec with ExportEntitiesApiService {

  def actorRefFactory: ActorSystem = system

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app)

  val largeFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/large/entities/sample/tsv"
  val validFireCloudEntitiesSampleSetTSVPath = "/api/workspaces/broad-dsde-dev/valid/entities/sample_set/tsv"
  val validFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"
  val invalidFireCloudEntitiesParticipantSetTSVPath = "/api/workspaces/broad-dsde-dev/invalid/entities/participant_set/tsv"

  "ExportEntitiesApiService-ExportEntitiesByType" - {

    "when calling GET on exporting LARGE (20K) sample set" - {
      "OK response is returned" in {
        Get(largeFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity shouldNot be(empty)
          response.headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          response.headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample.txt"))) should be(true)
        }
      }
    }

    "when calling GET on exporting a valid collection type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleSetTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity shouldNot be(empty)
          response.headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          response.headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample_set.zip"))) should be(true)
        }
      }
    }

    "when calling GET on exporting a valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity shouldNot be(empty)
          response.headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          response.headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample.txt"))) should be(true)
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

  }

}
