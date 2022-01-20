package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.firecloud.{EntityService, FireCloudConfig}
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.webservice.EntityApiService

import scala.concurrent.ExecutionContext

class EntitiesWithTypeServiceSpec extends BaseServiceSpec with EntityApiService with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)

  val validFireCloudPath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath + "/broad-dsde-dev/valid/"
  val invalidFireCloudPath = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath + "/broad-dsde-dev/invalid/"

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
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
      }
    }

  }
}
