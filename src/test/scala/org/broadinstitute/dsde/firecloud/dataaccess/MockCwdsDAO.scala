package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.StatusCodes.{BadRequest, EnhanceYourCalm, Forbidden, UnavailableForLegalReasons}
import org.broadinstitute.dsde.firecloud.dataaccess.LegacyFileTypes.{FILETYPE_PFB, FILETYPE_RAWLS, FILETYPE_TDR}
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, ImportServiceListResponse, UserInfo}
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import org.databiosphere.workspacedata.client.ApiException
import org.databiosphere.workspacedata.model.GenericJob
import org.databiosphere.workspacedata.model.GenericJob.{JobTypeEnum, StatusEnum}

import java.time.OffsetDateTime
import java.util.UUID

class MockCwdsDAO(
                   enabled: Boolean = true,
                   supportedFormats: List[String] = List("pfb", "tdrexport", "rawlsjson")
                 ) extends HttpCwdsDAO(enabled, supportedFormats) {
  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(
    "MockCWDS"
  )
  override def listJobsV1(workspaceId: String, runningOnly: Boolean)(implicit
                                                                     userInfo: UserInfo
  ): List[ImportServiceListResponse] = List()

  override def getJobV1(workspaceId: String, jobId: String)(implicit
                                                            userInfo: UserInfo
  ): ImportServiceListResponse =
    ImportServiceListResponse(jobId, "ReadyForUpsert", "pfb", None)

  override def importV1(
                         workspaceId: String,
                         importRequest: AsyncImportRequest
                       )(implicit userInfo: UserInfo): GenericJob = {
    importRequest.filetype match { case FILETYPE_PFB | FILETYPE_TDR | FILETYPE_RAWLS =>
      if (importRequest.url.contains("forbidden"))
        throw new ApiException(
          Forbidden.intValue,
          "Missing Authorization: Bearer token in header"
        )
      else if (importRequest.url.contains("bad.request"))
        throw new ApiException(
          BadRequest.intValue,
          "Bad request as reported by cwds"
        )
      else if (importRequest.url.contains("its.lawsuit.time"))
        throw new ApiException(
          UnavailableForLegalReasons.intValue,
          "cwds message"
        )
      else if (importRequest.url.contains("good")) makeJob(workspaceId)
      else
        throw new ApiException(
          EnhanceYourCalm.intValue,
          "enhance your calm"
        )
    case _ => ???
    }
  }

  private def makeJob(
                       workspaceId: String
                     ) = {
    val genericJob: GenericJob = new GenericJob
    genericJob.setJobId(UUID.randomUUID())
    genericJob.setStatus(StatusEnum.RUNNING)
    genericJob.setJobType(JobTypeEnum.DATA_IMPORT)
    // will this cause a problem in tests? Some test data has non-UUIDs.
    genericJob.setInstanceId(UUID.fromString(workspaceId))
    genericJob.setCreated(OffsetDateTime.now())
    genericJob.setUpdated(OffsetDateTime.now())
    genericJob
  }
}
