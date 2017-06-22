package org.broadinstitute.dsde.firecloud.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.webservice.ExportEntitiesApiService
import spray.http.StatusCodes._

class ExportEntitiesByTypeServiceSpec extends BaseServiceSpec with ExportEntitiesApiService {

  def actorRefFactory: ActorSystem = system

  val exportEntitiesByTypeConstructor: UserInfo => ExportEntitiesByTypeActor = ExportEntitiesByTypeActor.constructor(app)

  val validFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/valid/entities/sample/tsv"
  val invalidFireCloudEntitiesSampleTSVPath = "/api/workspaces/broad-dsde-dev/invalid/entities/sample/tsv"

  "EntityService-ExportEntitiesByType" - {

    "when calling GET on exporting a valid entity type" - {
      "OK response is returned" in {
        Get(validFireCloudEntitiesSampleTSVPath) ~> dummyUserIdHeaders("1234") ~> sealRoute(exportEntitiesRoutes) ~> check {
          handled should be(true)
          status should be(OK)
          response.entity shouldNot be(empty)
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
