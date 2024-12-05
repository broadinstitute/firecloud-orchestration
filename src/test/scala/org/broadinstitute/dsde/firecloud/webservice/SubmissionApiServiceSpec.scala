package org.broadinstitute.dsde.firecloud.webservice

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route.{seal => sealRoute}
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.mock.MockWorkspaceServer
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.OrchSubmissionRequest
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec

import scala.concurrent.ExecutionContext

final class SubmissionApiServiceSpec extends BaseServiceSpec with SubmissionApiService with SprayJsonSupport {

  override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  override def beforeAll(): Unit =
    MockWorkspaceServer.startWorkspaceServer()

  override def afterAll(): Unit =
    MockWorkspaceServer.stopWorkspaceServer()

  "SubmissionApiService" - {
    "when hitting the /submissions/queueStatus path" - {
      "with GET" - {
        "OK status is returned" in {
          Get("/submissions/queueStatus") ~> dummyAuthHeaders ~> sealRoute(submissionServiceRoutes) ~> check {
            status should equal(OK)
          }
        }
      }
    }
  }
}
