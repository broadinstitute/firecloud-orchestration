package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model.ImportServiceListResponse
import org.databiosphere.workspacedata.model.GenericJob
import org.databiosphere.workspacedata.model.GenericJob.{JobTypeEnum, StatusEnum}
import org.databiosphere.workspacedata.model.GenericJob.StatusEnum._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.OffsetDateTime
import java.util.UUID

class HttpCwdsDAOSpec extends AnyFreeSpec with Matchers {

  private val supportedFormats: List[String] = List("pfb", "tdrexport")

  // a dao that can be reused in multiple tests below
  private val cwdsDao: HttpCwdsDAO = new HttpCwdsDAO(true, supportedFormats)

  "HttpCwdsDAOSpec" - {

    "isEnabled" - {
      "when (true)" in {
        new HttpCwdsDAO(true, supportedFormats).isEnabled shouldBe true
      }
      "when (false)" in {
        new HttpCwdsDAO(false, supportedFormats).isEnabled shouldBe false
      }
    }

    "toImportServiceStatus" - {
      val statusTranslations = Map(
        // there is no effective difference between Translating and ReadyForUpsert for our purposes
        CREATED -> "Translating",
        QUEUED -> "Translating",
        RUNNING -> "ReadyForUpsert",
        SUCCEEDED -> "Done",
        ERROR -> "Error",
        CANCELLED -> "Error",
        UNKNOWN -> "Error"
      )

      statusTranslations.foreach { case (input, expected) =>
        s"input ($input) should translate to $expected" in {
          cwdsDao.toImportServiceStatus(input) shouldBe expected
        }
      }

      "should cover all possible statuses" in {
        GenericJob.StatusEnum.values().foreach { enumValue =>
          cwdsDao.toImportServiceStatus(enumValue) should not be "Unknown" // and should not throw
        }
      }
    }

    "toImportServiceListResponse" - {

      "should translate a job without an error message" in {
        val jobId: UUID = UUID.randomUUID()

        val input = new GenericJob()
        input.setJobId(jobId)
        input.setStatus(StatusEnum.RUNNING)
        input.setJobType(JobTypeEnum.DATA_IMPORT)
        input.setInstanceId(UUID.randomUUID())
        input.setCreated(OffsetDateTime.now())
        input.setUpdated(OffsetDateTime.now())

        val expected = ImportServiceListResponse(
          jobId = jobId.toString,
          status = "ReadyForUpsert",
          filetype = "DATA_IMPORT",
          message = None
        )

        cwdsDao.toImportServiceListResponse(input) shouldBe expected
      }

      "should translate a job with an error message" in {
        val jobId: UUID = UUID.randomUUID()
        val errMsg: String = "My error message for this unit test"

        val input = new GenericJob()
        input.setJobId(jobId)
        input.setStatus(StatusEnum.ERROR)
        input.setJobType(JobTypeEnum.DATA_IMPORT)
        input.setInstanceId(UUID.randomUUID())
        input.setCreated(OffsetDateTime.now())
        input.setUpdated(OffsetDateTime.now())
        input.setErrorMessage(errMsg)

        val expected = ImportServiceListResponse(
          jobId = jobId.toString,
          status = "Error",
          filetype = "DATA_IMPORT",
          message = Some(errMsg)
        )

        cwdsDao.toImportServiceListResponse(input) shouldBe expected
      }

    }

  }


}
