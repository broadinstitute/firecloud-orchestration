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

import scala.concurrent.ExecutionContext

class EntityApiServiceSpec extends BaseServiceSpec with EntityApiService with SprayJsonSupport {

  def actorRefFactory = system

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val entityServiceConstructor: (ModelSchema) => EntityService = EntityService.constructor(app)

  var workspaceServer: ClientAndServer = _
  val apiPrefix = FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesPath
  val validFireCloudEntitiesCopyPath = apiPrefix + "/broad-dsde-dev/valid/entities/copy"
  val invalidFireCloudEntitiesCopyPath = apiPrefix + "/broad-dsde-dev/invalid/entities/copy"

  val validEntityCopy = EntityCopyWithoutDestinationDefinition(
    sourceWorkspace = WorkspaceName(namespace = "broad-dsde-dev", name = "other-ws"),
    entityType = "sample",
    Seq("sample_01")
  )
  val invalidEntityCopy = EntityCopyWithoutDestinationDefinition(
    sourceWorkspace = WorkspaceName(namespace = "invalid", name = "other-ws"),
    entityType = "sample",
    Seq("sample_01")
  )

  def entityCopyWithDestination(copyDef: EntityCopyDefinition) = new EntityCopyDefinition(
    sourceWorkspace = copyDef.sourceWorkspace,
    destinationWorkspace = WorkspaceName("broad-dsde-dev", "valid"),
    entityType = copyDef.entityType,
    entityNames = copyDef.entityNames
  )

  override def beforeAll(): Unit = {
    workspaceServer = startClientAndServer(MockUtils.workspaceServerPort)
    // Valid/Invalid Copy cases
    workspaceServer
      .when(
        request()
          .withMethod("POST")
          .withPath(FireCloudConfig.Rawls.authPrefix + FireCloudConfig.Rawls.workspacesEntitiesCopyPath)
      )
      .respond(
        callback().withCallbackClass("org.broadinstitute.dsde.firecloud.mock.ValidEntityCopyCallback")
      )
  }

  override def afterAll(): Unit =
    workspaceServer.stop()

  "EntityService" - {

    "when calling POST on valid copy entities" - {
      "Created response is returned" in
        Post(validFireCloudEntitiesCopyPath, validEntityCopy) ~> dummyUserIdHeaders("1234") ~> sealRoute(
          entityRoutes
        ) ~> check {
          status should be(Created)
        }
    }

    "when calling POST on invalid copy entities" - {
      "NotFound response is returned" in
        Post(validFireCloudEntitiesCopyPath, invalidEntityCopy) ~> dummyUserIdHeaders("1234") ~> sealRoute(
          entityRoutes
        ) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
    }

    "when calling POST on copy entities in an unknown workspace" - {
      "NotFound response is returned with an ErrorReport" in
        Post(invalidFireCloudEntitiesCopyPath, validEntityCopy) ~> dummyUserIdHeaders("1234") ~> sealRoute(
          entityRoutes
        ) ~> check {
          status should be(NotFound)
          errorReportCheck("Rawls", NotFound)
        }
    }
  }

}
