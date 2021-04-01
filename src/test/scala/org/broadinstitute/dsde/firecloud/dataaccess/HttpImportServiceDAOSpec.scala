package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.util.ByteString
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impImportServiceResponse, impPfbImportResponse}
import org.broadinstitute.dsde.firecloud.model.{ImportServiceResponse, PfbImportRequest, PfbImportResponse}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.rawls.model.WorkspaceName
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.util.UUID

class HttpImportServiceDAOSpec extends AsyncFlatSpec with Matchers with SprayJsonSupport {

  implicit val system = ActorSystem("HttpGoogleCloudStorageDAOSpec")
  import system.dispatcher

  behavior of "HttpImportServiceDAO"

  val workspaceNamespace = "workspaceNamespace"
  val workspaceName = "workspaceName"
  val pfbUrl = "unittest-fixture-url"
  val pfbRequest = PfbImportRequest(Some(pfbUrl))

  it should "return Accepted if import service returns Created" in {
    val jobId = UUID.randomUUID()
    val importServicePayload = ImportServiceResponse(jobId.toString, "status", None).toJson.compactPrint

    val importServiceResponse = HttpResponse(status = Created,
      entity = HttpEntity.Strict(ContentTypes.`application/json`, ByteString(importServicePayload.getBytes)))

    val futureActual = new HttpImportServiceDAO().generateResponse(importServiceResponse, workspaceNamespace, workspaceName, pfbRequest)

    val expectedPayload = PfbImportResponse(
      pfbUrl,
      jobId.toString,
      WorkspaceName(workspaceNamespace, workspaceName))

    futureActual map { actual =>
      actual shouldBe RequestComplete(Accepted, expectedPayload)
    }

  }

}
