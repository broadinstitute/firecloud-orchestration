package org.broadinstitute.dsde.firecloud.dataaccess

import akka.http.scaladsl.model.StatusCodes.{BadRequest, EnhanceYourCalm, Forbidden, UnavailableForLegalReasons}
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.{FILETYPE_PFB, FILETYPE_RAWLS, FILETYPE_TDR}
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, ImportServiceListResponse, UserInfo}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
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
    importRequest.filetype match {
      case FILETYPE_PFB | FILETYPE_TDR =>
        if (importRequest.url.contains("forbidden"))
          throw new FireCloudExceptionWithErrorReport(
            ErrorReport(
              Forbidden,
              "Missing Authorization: Bearer token in header"
            )
          )
        else if (importRequest.url.contains("bad.request"))
          throw new FireCloudExceptionWithErrorReport(
            ErrorReport(
              BadRequest,
              "Bad request as reported by import service"
            )
          )
        else if (importRequest.url.contains("its.lawsuit.time"))
          throw new FireCloudExceptionWithErrorReport(
            ErrorReport(
              UnavailableForLegalReasons,
              "import service message"
            )
          )
        else if (importRequest.url.contains("good")) makeJob(workspaceId)
        else
          throw new FireCloudExceptionWithErrorReport(
            ErrorReport(EnhanceYourCalm, "Enhance your calm")
          )
      case FILETYPE_RAWLS => ???
      case _              => ???
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
