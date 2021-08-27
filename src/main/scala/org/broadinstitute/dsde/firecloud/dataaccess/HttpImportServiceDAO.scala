package org.broadinstitute.dsde.firecloud.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.{ImportServiceRequest, ImportServiceResponse, PfbImportRequest, PfbImportResponse, RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.FireCloudDirectiveUtils
import org.broadinstitute.dsde.firecloud.service.PerRequest.{PerRequestMessage, RequestComplete}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.utils.RestJsonClient
import org.broadinstitute.dsde.rawls.model.WorkspaceName

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class HttpImportServiceDAO(implicit val system: ActorSystem, implicit val materializer: Materializer, implicit val executionContext: ExecutionContext)
  extends ImportServiceDAO with RestJsonClient with SprayJsonSupport {

  override def importPFB(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequestMessage] = {
    doImport(workspaceNamespace, workspaceName, pfbRequest, "pfb")
  }

  override def importBatchUpsertJson(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequestMessage] = {
    doImport(workspaceNamespace, workspaceName, pfbRequest, "rawlsjson")
  }

  private def doImport(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest, filetype: String)(implicit userInfo: UserInfo): Future[PerRequestMessage] = {
    // the payload to Import Service sends "path" and filetype.  Here, we force-hardcode filetype because this API
    // should only be used for PFBs.
    val importServicePayload: ImportServiceRequest = ImportServiceRequest(path = pfbRequest.url.getOrElse(""), filetype = filetype)

    val importServiceUrl = FireCloudDirectiveUtils.encodeUri(s"${FireCloudConfig.ImportService.server}/$workspaceNamespace/$workspaceName/imports")

    userAuthedRequest(Post(importServiceUrl, importServicePayload))(userInfo) flatMap { isResponse =>
      generateResponse(isResponse, workspaceNamespace, workspaceName, pfbRequest)
    }
  }

  // separate method to ease unit testing
  protected[dataaccess] def generateResponse(isResponse: HttpResponse, workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest): Future[PerRequestMessage] = {
    isResponse match {
      case resp if resp.status == Created =>
        val importServiceResponse = Unmarshal(resp).to[ImportServiceResponse]

        // for backwards compatibility, we return Accepted(202), even though import service returns Created(201),
        // and we return a different response payload than what import service returns.

        importServiceResponse.map { resp =>
          val responsePayload:PfbImportResponse = PfbImportResponse(
            jobId = resp.jobId,
            url = pfbRequest.url.getOrElse(""),
            workspace = WorkspaceName(workspaceNamespace, workspaceName)
          )

          RequestComplete(Accepted, responsePayload)
        }
      case otherResp =>
        // see if we can extract errors
        val responseStringFuture = otherResp.entity match {
          case HttpEntity.Strict(_, data) =>
            Future.successful(data.utf8String)
          case nonStrictEntity =>
            nonStrictEntity.toStrict(10.seconds).map(_.data.utf8String)
        }
        responseStringFuture.map { responseString =>
          RequestCompleteWithErrorReport(otherResp.status, responseString)
        } recover {
          case t:Throwable =>
            RequestCompleteWithErrorReport(InternalServerError,
              s"Unexpected error reading response from import service: ${t.getMessage}",
              t)
        }
    }
  }
}
