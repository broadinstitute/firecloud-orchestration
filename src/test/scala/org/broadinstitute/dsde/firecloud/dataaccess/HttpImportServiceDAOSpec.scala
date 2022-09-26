package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.FILETYPE_PFB
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.{impAsyncImportResponse, impImportServiceResponse}
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, AsyncImportResponse, ImportServiceResponse, RequestCompleteWithErrorReport}
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
  val pfbRequest = AsyncImportRequest(pfbUrl, FILETYPE_PFB, None)

  val jobId = UUID.randomUUID()
  val importServicePayload = ImportServiceResponse(jobId.toString, "status", None).toJson.compactPrint

  val expectedSuccessPayload = AsyncImportResponse(
    pfbUrl,
    jobId.toString,
    WorkspaceName(workspaceNamespace, workspaceName))

  val expectedErrorPayload = s"this is a random error string: ${UUID.randomUUID()}"

  it should "return Accepted if import service returns Created with a strict entity" in {
    val importServiceResponse = HttpResponse(status = Created,
      entity = HttpEntity.Strict(ContentTypes.`application/json`, ByteString(importServicePayload)))

    val futureActual = new HttpImportServiceDAO().generateResponse(importServiceResponse, workspaceNamespace, workspaceName, pfbRequest)

    futureActual map { actual =>
      actual shouldBe RequestComplete(Accepted, expectedSuccessPayload)
    }
  }

  it should "return Accepted if import service returns Created with a non-strict entity" in {
    val importServiceResponse = HttpResponse(status = Created,
      entity = HttpEntity.Default(ContentTypes.`application/json`, importServicePayload.getBytes.length, Source.single(ByteString(importServicePayload))))

    val futureActual = new HttpImportServiceDAO().generateResponse(importServiceResponse, workspaceNamespace, workspaceName, pfbRequest)

    futureActual map { actual =>
      actual shouldBe RequestComplete(Accepted, expectedSuccessPayload)
    }
  }

  it should "bubble up status code and payload if import service returns non-Created with a strict entity" in {
    val importServiceResponse = HttpResponse(status = ImATeapot,
      entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString(expectedErrorPayload)))

    val futureActual = new HttpImportServiceDAO().generateResponse(importServiceResponse, workspaceNamespace, workspaceName, pfbRequest)

    futureActual map { actual =>
      actual shouldBe RequestCompleteWithErrorReport(ImATeapot, expectedErrorPayload)
    }
  }

  it should "bubble up status code and payload if import service returns non-Created with a non-strict entity" in {
    val importServiceResponse = HttpResponse(status = ImATeapot,
      entity = HttpEntity.Default(ContentTypes.`text/plain(UTF-8)`, expectedErrorPayload.getBytes.length, Source.single(ByteString(expectedErrorPayload))))

    val futureActual = new HttpImportServiceDAO().generateResponse(importServiceResponse, workspaceNamespace, workspaceName, pfbRequest)

    futureActual map { actual =>
      actual shouldBe RequestCompleteWithErrorReport(ImATeapot, expectedErrorPayload)
    }
  }
}
