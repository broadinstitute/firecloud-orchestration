package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import com.google.cloud.storage.StorageException
import org.broadinstitute.dsde.firecloud.dataaccess.MockImportServiceDAO
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{FirecloudModelSchema, ImportServiceResponse, ModelSchema, PfbImportRequest, RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PerRequest}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.parboiled.common.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually

import java.util.zip.ZipFile
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

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
        EntityService.unzipTSVs("duplicate_participants_nested_testbag", zip) { (_, _) =>
          Future.successful(RequestComplete(StatusCodes.OK))
        }
      }
      ex.errorReport.message shouldEqual "More than one participants.tsv file found in bagit duplicate_participants_nested_testbag"
    }

    "with multiple samples in a zip with a flat file structure" in {
      val zip = new ZipFile("src/test/resources/testfiles/bagit/duplicate_samples_nested_testbag.zip")

      val ex = intercept[FireCloudExceptionWithErrorReport] {
        EntityService.unzipTSVs("duplicate_samples_nested_testbag", zip) { (_, _) =>
          Future.successful(RequestComplete(StatusCodes.OK))
        }
      }
      ex.errorReport.message shouldEqual "More than one samples.tsv file found in bagit duplicate_samples_nested_testbag"
    }
  }

  "EntityService.importEntitiesFromTSV()" - {
    // TODO: foreach entity, membership, update TSVs:

    val tsvParticipants = FileUtils.readAllTextFromResource("testfiles/tsv/ADD_PARTICIPANTS.txt")
    val tsvInvalid = FileUtils.readAllTextFromResource("testfiles/tsv/TEST_INVALID_COLUMNS.txt")

    val userToken: UserInfo = UserInfo("me@me.com", OAuth2BearerToken(""), 3600, "111")

    "should return Created with an import jobId for async=true when all is well" in {
      val testImportDAO = new SuccessfulImportServiceDAO
      val entityService = getEntityService(mockImportServiceDAO = testImportDAO)
      val response =
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken, isAsync = true).futureValue
      response shouldBe testImportDAO.successDefinition
    }

    "should return OK with the entity type for async=false when all is well" in {
      val entityService = getEntityService()
      val response =
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken).futureValue // isAsync defaults to false, so we omit it here
      response shouldBe RequestComplete(StatusCodes.OK, "participant")
    }

    "should return error for async=true when failed to write to GCS" in {
      val testGoogleDAO = new ErroringGoogleServicesDAO
      val entityService = getEntityService(mockGoogleServicesDAO = testGoogleDAO)
      val caught = intercept[StorageException] {
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken, isAsync = true).futureValue
      }

      caught shouldBe testGoogleDAO.errorDefinition
    }

    "should return error for async=true when import service returns sync error" in {
      val testImportDAO = new ErroringImportServiceDAO
      val entityService = getEntityService(mockImportServiceDAO = testImportDAO)
      val response =
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken, isAsync = true).futureValue
      response shouldBe testImportDAO.errorDefinition
    }

    List(true, false) foreach { async =>
      s"should return error for async=$async when TSV is unparsable" in {
        val entityService = getEntityService()
        val caught = intercept[FireCloudExceptionWithErrorReport] {
          entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
            tsvInvalid, userToken, async)
        }
        caught.errorReport.statusCode should contain(StatusCodes.BadRequest)
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

  class ErroringGoogleServicesDAO extends MockGoogleServicesDAO {
    def errorDefinition: Exception = new StorageException(418, "intentional unit test failure")

    override def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, objectContents: Array[Byte]): GcsPath =
    // throw a 418 so unit tests have an easy way to distinguish this error vs an error somewhere else in the stack
    throw errorDefinition
  }

  class SuccessfulImportServiceDAO extends MockImportServiceDAO {
    def successDefinition: RequestComplete[(StatusCodes.Success, ImportServiceResponse)] = RequestComplete(StatusCodes.Created, ImportServiceResponse("unit-test-job-id", "unit-test-created-status", None))

    override def importBatchUpsertJson(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = {
      Future.successful(successDefinition)
    }
  }

  class ErroringImportServiceDAO extends MockImportServiceDAO {

    // return a 429 so unit tests have an easy way to distinguish this error vs an error somewhere else in the stack
    def errorDefinition: RequestComplete[(StatusCode, ErrorReport)] = RequestCompleteWithErrorReport(StatusCodes.TooManyRequests, "intentional ErroringImportServiceDAO error")

    override def importBatchUpsertJson(workspaceNamespace: String, workspaceName: String, pfbRequest: PfbImportRequest)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = {
      Future.successful(errorDefinition)
    }
  }


}
