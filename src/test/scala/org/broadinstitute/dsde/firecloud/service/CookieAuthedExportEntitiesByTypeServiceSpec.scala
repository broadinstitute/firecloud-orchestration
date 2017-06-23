package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.CookieAuthedApiService
import spray.http.StatusCodes._
import spray.http.{FormData, HttpHeaders}

class CookieAuthedExportEntitiesByTypeServiceSpec extends BaseServiceSpec with CookieAuthedApiService {

  def actorRefFactory: ActorSystem = system

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app)

  val validFireCloudEntitiesLargeSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/large/entities/sample/tsv"
  val validFireCloudEntitiesSampleSetTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/valid/entities/sample_set/tsv"
  val validFireCloudEntitiesSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"
  val invalidFireCloudEntitiesParticipantSetTSVPath = "/cookie-authed/workspaces/broad-dsde-dev/invalid/entities/participant_set/tsv"

  "CookieAuthedApiService-ExportEntitiesByType" - {

    "when calling POST on exporting LARGE (20K) sample set" - {
      "OK response is returned" in {
        Post(validFireCloudEntitiesLargeSampleTSVPath, FormData(Seq("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity shouldNot be(empty)
          response.headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          response.headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample.txt"))) should be(true)
        }
      }
    }

    "when calling POST on exporting a valid collection type" - {
      "OK response is returned" in {
        Post(validFireCloudEntitiesSampleSetTSVPath, FormData(Seq("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity shouldNot be(empty)
          response.headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          response.headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample_set.zip"))) should be(true)
        }
      }
    }

    "when calling POST on exporting a valid entity type" - {
      "OK response is returned" in {
        Post(validFireCloudEntitiesSampleTSVPath, FormData(Seq("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity shouldNot be(empty)
          response.headers.contains(HttpHeaders.Connection("Keep-Alive")) should be(true)
          response.headers.contains(HttpHeaders.`Content-Disposition`.apply("attachment", Map("filename" -> "sample.txt"))) should be(true)
        }
      }
    }

    "when calling POST on exporting an invalid collection type" - {
      "NotFound response is returned" in {
        Post(invalidFireCloudEntitiesParticipantSetTSVPath, FormData(Seq("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
        }
      }
    }

    "when calling POST on exporting an invalid entity type" - {
      "NotFound response is returned" in {
        Post(invalidFireCloudEntitiesSampleTSVPath, FormData(Seq("FCtoken"->"token"))) ~> sealRoute(cookieAuthedRoutes) ~> check {
          handled should be(true)
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

  }

}
