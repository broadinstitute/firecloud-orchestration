package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.StatusCodes.{BadRequest, InternalServerError, TooManyRequests}

import java.util.zip.ZipFile
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PerRequest}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.scalatest.BeforeAndAfterEach
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.firecloud.dataaccess.MockImportServiceDAO
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.{FirecloudModelSchema, ModelSchema, PfbImportRequest, RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.parboiled.common.FileUtils
import org.scalatest.RecoverMethods.recoverToExceptionIf
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits._

class EntityServiceSpec extends BaseServiceSpec with BeforeAndAfterEach with Eventually {
  override def beforeEach(): Unit = {
    searchDao.reset
  }

  override def afterEach(): Unit = {
    searchDao.reset
  }

  "EntityClient should extract TSV files out of bagit zips" - {
    "with neither participants nor samples in the zip" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/nothingbag.zip")
      EntityService.unzipTSVs("nothingbag", zip) { (participants, samples) =>
        participants shouldBe None
        samples shouldBe None
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with both participants and samples in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/flat_testbag.zip")
      EntityService.unzipTSVs("flat_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv in a flat file structure")
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv in a flat file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with only participants in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/participants_only_flat_testbag.zip")
      EntityService.unzipTSVs("participants_only_flat_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv all alone in a flat file structure")
        samples.map(_.stripLineEnd) shouldEqual None
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with only samples in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/samples_only_flat_testbag.zip")
      EntityService.unzipTSVs("samples_only_flat_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual None
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv all alone in a flat file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with both participants and samples in a zip with a nested file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/nested_testbag.zip")
      EntityService.unzipTSVs("nested_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv in a nested file structure")
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv in a nested file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with both participants and samples and an extra tsv file in a zip with a nested file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/extra_file_nested_testbag.zip")
      EntityService.unzipTSVs("extra_file_nested_testbag", zip) { (participants, samples) =>
        participants.map(_.stripLineEnd) shouldEqual Some("imagine this is a participants.tsv in a nested file structure")
        samples.map(_.stripLineEnd) shouldEqual Some("imagine this is a samples.tsv in a nested file structure")
        Future.successful(RequestComplete(StatusCodes.OK))
      }
    }

    "with multiple participants in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/duplicate_participants_nested_testbag.zip")

      val ex = intercept[FireCloudExceptionWithErrorReport] {
        EntityService.unzipTSVs("duplicate_participants_nested_testbag", zip) { (participants, samples) =>
          Future.successful(RequestComplete(StatusCodes.OK))
        }
      }
      ex.errorReport.message shouldEqual "More than one participants.tsv file found in bagit duplicate_participants_nested_testbag"
    }

    "with multiple samples in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/duplicate_samples_nested_testbag.zip")

      val ex = intercept[FireCloudExceptionWithErrorReport] {
        EntityService.unzipTSVs("duplicate_samples_nested_testbag", zip) { (participants, samples) =>
          Future.successful(RequestComplete(StatusCodes.OK))
        }
      }
      ex.errorReport.message shouldEqual "More than one samples.tsv file found in bagit duplicate_samples_nested_testbag"
    }
  }

  "EntityService.importEntitiesFromTSV()" - {
    // TODO: foreach entity, membership, update TSVs:

    "should return Created with an import jobId for async=true when all is well" is (pending)

    "should return OK with the entity type for async=false when all is well" is (pending)

    "should return error for async=true when failed to write to GCS" is (pending)

    "should return error for async=true when import service returns sync error" in {
      // read TEST_INVALID_COLUMNS.txt from resources/testfiles/tsv
      val tsvString = FileUtils.readAllTextFromResource("testfiles/tsv/ADD_PARTICIPANTS.txt")

      val entityService = getEntityService(mockImportServiceDAO = new ErroringImportServiceDAO)

      val userToken: UserInfo = UserInfo("me@me.com", OAuth2BearerToken(""), 3600, "111")

      val response =
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvString, userToken, isAsync = true).futureValue

      val expected = new ErroringImportServiceDAO().errorDefinition()

      response shouldBe expected
    }

    List(true, false) foreach { async =>
      s"should return error for async=$async when TSV is unparsable" in {
        // read TEST_INVALID_COLUMNS.txt from resources/testfiles/tsv
        val tsvString = FileUtils.readAllTextFromResource("testfiles/tsv/TEST_INVALID_COLUMNS.txt")

        val entityService = getEntityService()

        val userToken: UserInfo = UserInfo("me@me.com", OAuth2BearerToken(""), 3600, "111")

        val caught = intercept[FireCloudExceptionWithErrorReport] {
          entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName", tsvString, userToken, async)
        }
        caught.errorReport.statusCode should contain(BadRequest)
      }
    }
  }

  private def getEntityService(mockGoogleServicesDAO: MockGoogleServicesDAO = new MockGoogleServicesDAO,
                               mockImportServiceDAO: MockImportServiceDAO = new MockImportServiceDAO) = {
    val application = app.copy(googleServicesDAO = mockGoogleServicesDAO, importServiceDAO = mockImportServiceDAO)

    // instantiate an EntityService, specify importServiceDAO and googleServicesDAO
    implicit val modelSchema: ModelSchema = FirecloudModelSchema
    EntityService.constructor(application)(modelSchema)(global)
  }

  class ErroringImportServiceDAO extends MockImportServiceDAO {

    def errorDefinition() = RequestCompleteWithErrorReport(TooManyRequests, "intentional ErroringImportServiceDAO error")


    override def importBatchUpsertJson(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = {
      // return a 429 so unit tests have an easy way to distinguish this error vs an error somewhere else in the stack
      Future.successful(errorDefinition)
    }
  }


}
