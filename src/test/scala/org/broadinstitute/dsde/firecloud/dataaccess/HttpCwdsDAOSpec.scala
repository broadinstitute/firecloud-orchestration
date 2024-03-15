package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, ImportOptions, ImportServiceListResponse}
import org.databiosphere.workspacedata.model.{GenericJob, ImportRequest}
import org.databiosphere.workspacedata.model.GenericJob.{JobTypeEnum, StatusEnum}
import org.databiosphere.workspacedata.model.GenericJob.StatusEnum._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import scala.jdk.CollectionConverters._

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

    "toCwdsImportType" - {
      "input (pfb) should translate to PFB" in {
        cwdsDao.toCwdsImportType("pfb") shouldBe ImportRequest.TypeEnum.PFB
      }
      "input (tdrexport) should translate to TDRMANIFEST" in {
        cwdsDao.toCwdsImportType("tdrexport") shouldBe ImportRequest.TypeEnum.TDRMANIFEST
      }
      "other input should throw" in {
        a [FireCloudException] should be thrownBy cwdsDao.toCwdsImportType("something-else")
      }
    }

    "toCwdsImportRequest" - {
      "should translate an import request with no options" in {
        val testURI: URI = URI.create("https://example.com/")

        val input = AsyncImportRequest(url = testURI.toString,
          filetype = "pfb",
          options = None)

        val expected = new ImportRequest()
        expected.setUrl(testURI)
        expected.setType(ImportRequest.TypeEnum.PFB)
        expected.setOptions(Map.empty[String,Object].asJava)

        cwdsDao.toCwdsImportRequest(input) shouldBe expected
      }

      "should translate an import request with empty options" in {
        val testURI: URI = URI.create("https://example.com/")

        val input = AsyncImportRequest(url = testURI.toString,
          filetype = "pfb",
          options = Some(ImportOptions(tdrSyncPermissions = None)))

        val expected = new ImportRequest()
        expected.setUrl(testURI)
        expected.setType(ImportRequest.TypeEnum.PFB)
        expected.setOptions(Map.empty[String,Object].asJava)

        cwdsDao.toCwdsImportRequest(input) shouldBe expected
      }

      "should translate an import request with tdrSyncPermissions=true" in {
        val testURI: URI = URI.create("https://example.com/")

        val input = AsyncImportRequest(url = testURI.toString,
          filetype = "pfb",
          options = Some(ImportOptions(tdrSyncPermissions = Some(true))))

        val expected = new ImportRequest()
        expected.setUrl(testURI)
        expected.setType(ImportRequest.TypeEnum.PFB)
        expected.setOptions(Map[String,Object]("tdrSyncPermissions" -> true.asInstanceOf[Object]).asJava)

        cwdsDao.toCwdsImportRequest(input) shouldBe expected
      }

      "should translate an import request with tdrSyncPermissions=false" in {
        val testURI: URI = URI.create("https://example.com/")

        val input = AsyncImportRequest(url = testURI.toString,
          filetype = "pfb",
          options = Some(ImportOptions(tdrSyncPermissions = Some(false))))

        val expected = new ImportRequest()
        expected.setUrl(testURI)
        expected.setType(ImportRequest.TypeEnum.PFB)
        expected.setOptions(Map[String,Object]("tdrSyncPermissions" -> false.asInstanceOf[Object]).asJava)

        cwdsDao.toCwdsImportRequest(input) shouldBe expected
      }
    }


  }


}
