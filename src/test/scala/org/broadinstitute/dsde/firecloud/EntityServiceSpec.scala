package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpResponse, StatusCode, StatusCodes}
import com.google.cloud.storage.StorageException
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.FILETYPE_RAWLS
import org.broadinstitute.dsde.firecloud.dataaccess.{MockCwdsDAO, MockImportServiceDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, EntityUpdateDefinition, FirecloudModelSchema, ImportServiceListResponse, ImportServiceResponse, ModelSchema, RequestCompleteWithErrorReport, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PerRequest}
import org.broadinstitute.dsde.rawls.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, times, verify, when}
import org.parboiled.common.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar.{mock => mockito}

import java.util.UUID
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

  private def dummyUserInfo(tokenStr: String) = UserInfo("dummy", OAuth2BearerToken(tokenStr), -1, "dummy")

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

  "EntityService.listJobs" - {
    "should concatenate results from cWDS and Import Service" in {
      val importServiceResponse = List(
        ImportServiceListResponse("jobId1", "status1", "filetype1", None),
        ImportServiceListResponse("jobId2", "status2", "filetype2", None)
      )
      val cwdsResponse = List(
        ImportServiceListResponse("jobId3", "status3", "filetype3", None),
        ImportServiceListResponse("jobId4", "status4", "filetype4", None)
      )

      listJobsTestImpl(importServiceResponse, cwdsResponse)
    }

    "should return empty list if both cWDS and Import Service return empty lists" in {
      val importServiceResponse = List()
      val cwdsResponse = List()

      listJobsTestImpl(importServiceResponse, cwdsResponse)
    }

    "should return results if Import Service has results but cWDS is empty" in {
      val importServiceResponse = List(
        ImportServiceListResponse("jobId1", "status1", "filetype1", None),
        ImportServiceListResponse("jobId2", "status2", "filetype2", None)
      )
      val cwdsResponse = List()

      listJobsTestImpl(importServiceResponse, cwdsResponse)
    }

    "should return results if cWDS has results but Import Service is empty" in {
      val importServiceResponse = List()
      val cwdsResponse = List(
        ImportServiceListResponse("jobId1", "status1", "filetype1", None),
        ImportServiceListResponse("jobId2", "status2", "filetype2", None)
      )

      listJobsTestImpl(importServiceResponse, cwdsResponse)
    }

    "should not call cWDS if cWDS is not enabled" in {
      // set up mocks
      val importServiceDAO = mockito[MockImportServiceDAO]
      val cwdsDAO = mockito[MockCwdsDAO]
      val rawlsDAO = mockito[MockRawlsDAO]

      val importServiceResponse = List(
        ImportServiceListResponse("jobId1", "status1", "filetype1", None),
        ImportServiceListResponse("jobId2", "status2", "filetype2", None)
      )
      val cwdsResponse = List(
        ImportServiceListResponse("jobId3", "status3", "filetype3", None),
        ImportServiceListResponse("jobId4", "status4", "filetype4", None)
      )

      when(importServiceDAO.listJobs(any[String], any[String], any[Boolean])(any[UserInfo])).thenReturn(Future.successful(importServiceResponse))
      when(cwdsDAO.listJobsV1(any[String], any[Boolean])(any[UserInfo])).thenReturn(cwdsResponse)
      when(cwdsDAO.isEnabled).thenReturn(false)

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // list jobs via entity service
      val actual = entityService.listJobs("workspaceNamespace", "workspaceName", runningOnly = true, dummyUserInfo("mytoken")).futureValue

      // verify Rawls get-workspace was NOT called
      verify(rawlsDAO, never).getWorkspace(any[String], any[String])(any[WithAccessToken])
      // verify cwds list-jobs was NOT called
      verify(cwdsDAO, never).listJobsV1(any[String], any[Boolean])(any[UserInfo])

      // verify the response only contains Import Service jobs
      actual should contain theSameElementsAs importServiceResponse
    }

    def listJobsTestImpl(importServiceResponse: List[ImportServiceListResponse], cwdsResponse: List[ImportServiceListResponse]) = {
      // set up mocks
      val importServiceDAO = mockito[MockImportServiceDAO]
      val cwdsDAO = mockito[MockCwdsDAO]

      when(importServiceDAO.listJobs(any[String], any[String], any[Boolean])(any[UserInfo])).thenReturn(Future.successful(importServiceResponse))
      when(cwdsDAO.listJobsV1(any[String], any[Boolean])(any[UserInfo])).thenReturn(cwdsResponse)
      when(cwdsDAO.isEnabled).thenReturn(true)

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // list jobs via entity service
      val actual = entityService.listJobs("workspaceNamespace", "workspaceName", runningOnly = true, dummyUserInfo("mytoken")).futureValue

      actual should contain theSameElementsAs (importServiceResponse ++ cwdsResponse)
    }


  }

  private def getEntityService(mockGoogleServicesDAO: MockGoogleServicesDAO = new MockGoogleServicesDAO,
                               mockImportServiceDAO: MockImportServiceDAO = new MockImportServiceDAO,
                               rawlsDAO: MockRawlsDAO = new MockRawlsDAO,
                               cwdsDAO: MockCwdsDAO = new MockCwdsDAO) = {
    val application = app.copy(googleServicesDAO = mockGoogleServicesDAO, rawlsDAO = rawlsDAO, importServiceDAO = mockImportServiceDAO, cwdsDAO = cwdsDAO)

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
