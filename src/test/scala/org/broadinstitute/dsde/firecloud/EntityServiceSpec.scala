package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpResponse, StatusCode, StatusCodes}
import com.google.cloud.storage.StorageException
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.FILETYPE_RAWLS
import org.broadinstitute.dsde.firecloud.dataaccess.{MockImportServiceDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, EntityUpdateDefinition, FirecloudModelSchema, ImportServiceResponse, ModelSchema, RequestCompleteWithErrorReport, UserInfo}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PerRequest}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.parboiled.common.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar.{mock => mockito}

import java.util.zip.ZipFile
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

class EntityServiceSpec extends BaseServiceSpec with BeforeAndAfterEach {
  override def beforeEach(): Unit = {
    searchDao.reset()
  }

  override def afterEach(): Unit = {
    searchDao.reset()
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
    val tsvParticipants = FileUtils.readAllTextFromResource("testfiles/tsv/ADD_PARTICIPANTS.txt")
    val tsvMembership = FileUtils.readAllTextFromResource("testfiles/tsv/MEMBERSHIP_SAMPLE_SET.tsv")
    val tsvUpdate = FileUtils.readAllTextFromResource("testfiles/tsv/UPDATE_SAMPLES.txt")
    val tsvInvalid = FileUtils.readAllTextFromResource("testfiles/tsv/TEST_INVALID_COLUMNS.txt")

    val userToken: UserInfo = UserInfo("me@me.com", OAuth2BearerToken(""), 3600, "111")

    // (tsvType, tsvData)
    val asyncTSVs = List(
      ("upsert", tsvParticipants),
      ("membership", tsvMembership),
      ("update", tsvUpdate))

    asyncTSVs foreach {
      case (tsvType, tsvData) =>
        s"should return Created with an import jobId for (async=true + $tsvType TSV)" in {
          val testImportDAO = new SuccessfulImportServiceDAO
          val entityService = getEntityService(mockImportServiceDAO = testImportDAO)
          val response =
            entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
              tsvData, userToken, isAsync = true).futureValue
          response shouldBe testImportDAO.successDefinition
        }
    }

    // (tsvType, expectedEntityType, tsvData)
    val goodTSVs = List(
      ("upsert", "participant", tsvParticipants),
      ("membership", "sample_set", tsvMembership),
      ("update", "sample", tsvUpdate))

    goodTSVs foreach {
      case (tsvType, expectedEntityType, tsvData) =>
        s"should return OK with the entity type for (async=false + $tsvType TSV)" in {
          val entityService = getEntityService()
          val response =
            entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
              tsvData, userToken).futureValue // isAsync defaults to false, so we omit it here
          response shouldBe RequestComplete(StatusCodes.OK, expectedEntityType)
        }

        s"should call the appropriate upsert/update method for (async=false + $tsvType TSV)" in {
          val mockedRawlsDAO = mockito[MockRawlsDAO] // mocking the mock
          when(mockedRawlsDAO.batchUpdateEntities(any[String], any[String], any[String],
            any[Seq[EntityUpdateDefinition]])(any[UserInfo]))
            .thenReturn(Future.successful(HttpResponse(StatusCodes.NoContent)))

          when(mockedRawlsDAO.batchUpsertEntities(any[String], any[String], any[String],
            any[Seq[EntityUpdateDefinition]])(any[UserInfo]))
            .thenReturn(Future.successful(HttpResponse(StatusCodes.NoContent)))

          val entityService = getEntityService(rawlsDAO = mockedRawlsDAO)
          val _ =
            entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
              tsvData, userToken).futureValue // isAsync defaults to false, so we omit it here

          if (tsvType == "update") {
            verify(mockedRawlsDAO, times(1)).batchUpdateEntities(
              ArgumentMatchers.eq("workspaceNamespace"), ArgumentMatchers.eq("workspaceName"),
              ArgumentMatchers.eq(expectedEntityType), any[Seq[EntityUpdateDefinition]])(any[UserInfo])
          } else {
            verify(mockedRawlsDAO, times(1)).batchUpsertEntities(
              ArgumentMatchers.eq("workspaceNamespace"), ArgumentMatchers.eq("workspaceName"),
              ArgumentMatchers.eq(expectedEntityType), ArgumentMatchers.any[Seq[EntityUpdateDefinition]])(ArgumentMatchers.any[UserInfo])

          }

        }

    }

    "should return error for (async=true) when failed to write to GCS" in {
      val testGoogleDAO = new ErroringGoogleServicesDAO
      val entityService = getEntityService(mockGoogleServicesDAO = testGoogleDAO)
      val caught = intercept[StorageException] {
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken, isAsync = true).futureValue
      }

      caught shouldBe testGoogleDAO.errorDefinition
    }

    "should return error for (async=true) when import service returns sync error" in {
      val testImportDAO = new ErroringImportServiceDAO
      val entityService = getEntityService(mockImportServiceDAO = testImportDAO)
      val response =
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken, isAsync = true).futureValue
      response shouldBe testImportDAO.errorDefinition
    }

    List(true, false) foreach { async =>
      s"should return error for (async=$async) when TSV is unparsable" in {
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
                               mockImportServiceDAO: MockImportServiceDAO = new MockImportServiceDAO,
                               rawlsDAO: MockRawlsDAO = new MockRawlsDAO) = {
    val application = app.copy(googleServicesDAO = mockGoogleServicesDAO, rawlsDAO = rawlsDAO, importServiceDAO = mockImportServiceDAO)

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

    override def importJob(workspaceNamespace: String, workspaceName: String, importRequest: AsyncImportRequest, isUpsert: Boolean)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = {
      importRequest.filetype match {
        case FILETYPE_RAWLS => Future.successful(successDefinition)
        case _ => ???
      }
    }
  }

  class ErroringImportServiceDAO extends MockImportServiceDAO {

    // return a 429 so unit tests have an easy way to distinguish this error vs an error somewhere else in the stack
    def errorDefinition: RequestComplete[(StatusCode, ErrorReport)] = RequestCompleteWithErrorReport(StatusCodes.TooManyRequests, "intentional ErroringImportServiceDAO error")

    override def importJob(workspaceNamespace: String, workspaceName: String, importRequest: AsyncImportRequest, isUpsert: Boolean)(implicit userInfo: UserInfo): Future[PerRequest.PerRequestMessage] = {
      importRequest.filetype match {
        case FILETYPE_RAWLS => Future.successful(errorDefinition)
        case _ => ???
      }
    }
  }

}
