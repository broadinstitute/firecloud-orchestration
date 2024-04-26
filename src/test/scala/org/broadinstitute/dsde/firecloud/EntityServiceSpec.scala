package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpResponse, StatusCode, StatusCodes}
import com.google.cloud.storage.StorageException
import org.broadinstitute.dsde.firecloud.dataaccess.ImportServiceFiletypes.FILETYPE_RAWLS
import org.broadinstitute.dsde.firecloud.dataaccess.{MockCwdsDAO, MockImportServiceDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, EntityUpdateDefinition, FirecloudModelSchema, ImportOptions, ImportServiceListResponse, ImportServiceResponse, ModelSchema, RequestCompleteWithErrorReport, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PerRequest}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.databiosphere.workspacedata.client.ApiException
import org.databiosphere.workspacedata.model.GenericJob
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doThrow, never, times, verify, when}
import org.parboiled.common.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.{mock => mockito}

import java.util.UUID
import java.util.zip.ZipFile
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.reflect.classTag

class EntityServiceSpec extends BaseServiceSpec with BeforeAndAfterEach {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource("EntityServiceSpec")

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

        s"should send $expectedEntityType tsv to cWDS with appropriate options when cWDS is enabled and supports rawlsjson" in {
            // set up mocks
            val importServiceDAO = mockito[MockImportServiceDAO]
            val cwdsDAO = mockito[MockCwdsDAO]
            val rawlsDAO = mockito[MockRawlsDAO]

            // inject mocks to entity service
            val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO, rawlsDAO = rawlsDAO)

            // set up behaviors
            val genericJob: GenericJob = new GenericJob
            genericJob.setJobId(UUID.randomUUID())
            // the "new MockRawlsDAO()" here is only to get access to a pre-canned WorkspaceResponse object
            val workspaceResponse = new MockRawlsDAO().rawlsWorkspaceResponseWithAttributes

            when(cwdsDAO.isEnabled).thenReturn(true)
            when(cwdsDAO.getSupportedFormats).thenReturn(List("pfb","tdrexport", "rawlsjson"))
            when(importServiceDAO.importJob(any[String], any[String], any[AsyncImportRequest], any[Boolean])(any[UserInfo]))
              .thenReturn(Future.successful(RequestComplete(StatusCodes.Accepted, "")))
            when(rawlsDAO.getWorkspace(any[String], any[String])(any[UserInfo]))
              .thenReturn(Future.successful(workspaceResponse))

            entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName", tsvData, dummyUserInfo("token"), true).futureValue

          val argumentCaptor = ArgumentCaptor.forClass(classTag[AsyncImportRequest].runtimeClass).asInstanceOf[ArgumentCaptor[AsyncImportRequest]]

          verify(cwdsDAO, times(1))
            .importV1(any[String], argumentCaptor.capture())(any[UserInfo])
          val capturedRequest = argumentCaptor.getValue
          capturedRequest.options should be(Some(ImportOptions(None, Some(tsvType != "update"))))
          verify(importServiceDAO, never)
              .importJob(any[String], any[String], any[AsyncImportRequest], any[Boolean])(any[UserInfo])
          verify(rawlsDAO, times(1))
              .getWorkspace(any[String], any[String])(any[UserInfo])

          }
    }

//    "should send sample tsv to cWDS with appropriate options when cWDS is enabled and supports rawlsjson" in {
//      // set up mocks
//      val importServiceDAO = mockito[MockImportServiceDAO]
//      val cwdsDAO = mockito[MockCwdsDAO]
//      val rawlsDAO = mockito[MockRawlsDAO]
//
//      // inject mocks to entity service
//      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO, rawlsDAO = rawlsDAO)
//
//      // set up behaviors
//      val genericJob: GenericJob = new GenericJob
//      genericJob.setJobId(UUID.randomUUID())
//      // the "new MockRawlsDAO()" here is only to get access to a pre-canned WorkspaceResponse object
//      val workspaceResponse = new MockRawlsDAO().rawlsWorkspaceResponseWithAttributes
//
//      when(cwdsDAO.isEnabled).thenReturn(true)
//      when(cwdsDAO.getSupportedFormats).thenReturn(List("pfb","tdrexport", "rawlsjson"))
//      when(importServiceDAO.importJob(any[String], any[String], any[AsyncImportRequest], any[Boolean])(any[UserInfo]))
//        .thenReturn(Future.successful(RequestComplete(StatusCodes.Accepted, "")))
//      when(rawlsDAO.getWorkspace(any[String], any[String])(any[UserInfo]))
//        .thenReturn(Future.successful(workspaceResponse))
//
//      entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName", tsvParticipants, dummyUserInfo("token"), true).futureValue
//
//      val argumentCaptor = ArgumentCaptor.forClass(classTag[AsyncImportRequest].runtimeClass).asInstanceOf[ArgumentCaptor[AsyncImportRequest]]
//
//      //            verify(mockedRawlsDAO, times(1)).batchUpdateEntities(
//      //              ArgumentMatchers.eq("workspaceNamespace"), ArgumentMatchers.eq("workspaceName"),
//      //              ArgumentMatchers.eq(expectedEntityType), any[Seq[EntityUpdateDefinition]])(any[UserInfo])
//
//      //            verify(cwdsDAO, cwdsCallCount)
//      //              .importV1(any[String], any[AsyncImportRequest])(any[UserInfo])
//      verify(cwdsDAO, times(1))
//        .importV1(any[String], argumentCaptor.capture())(any[UserInfo])
//      val capturedRequest = argumentCaptor.getValue
//      capturedRequest.options should be(Some(ImportOptions(None, Some(tsvType != "update"))))
//      verify(importServiceDAO, never)
//        .importJob(any[String], any[String], any[AsyncImportRequest], any[Boolean])(any[UserInfo])
//      verify(rawlsDAO, times(1))
//        .getWorkspace(any[String], any[String])(any[UserInfo])
//
//    }

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

  "EntityService.importJob" - {
    "should send to cWDS when cWDS is enabled and supports the filetype" in {
      importJobTestImpl(
        cwdsEnabled = true,
        cwdsSupportedFormats = List("pfb","tdrexport"),
        importFiletype = "tdrexport",
        expectUsingCwds = true)

    }
    "should send to Import Service when cWDS is disabled, even if cWDS supports the filetype" in {
      importJobTestImpl(
        cwdsEnabled = false,
        cwdsSupportedFormats = List("pfb","tdrexport"),
        importFiletype = "tdrexport",
        expectUsingCwds = false)
    }
    "should send to Import Service for filetypes cWDS does not support, even when cWDS is enabled" in {
      importJobTestImpl(
        cwdsEnabled = true,
        cwdsSupportedFormats = List("pfb"),
        importFiletype = "tdrexport",
        expectUsingCwds = false)
    }

    def importJobTestImpl(cwdsEnabled: Boolean,
                                  cwdsSupportedFormats: List[String],
                                  importFiletype: String,
                                  expectUsingCwds: Boolean) = {
      // set up mocks
      val importServiceDAO = mockito[MockImportServiceDAO]
      val cwdsDAO = mockito[MockCwdsDAO]
      val rawlsDAO = mockito[MockRawlsDAO]

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO, rawlsDAO = rawlsDAO)

      // set up behaviors
      val genericJob: GenericJob = new GenericJob
      genericJob.setJobId(UUID.randomUUID())
      // the "new MockRawlsDAO()" here is only to get access to a pre-canned WorkspaceResponse object
      val workspaceResponse = new MockRawlsDAO().rawlsWorkspaceResponseWithAttributes

      when(cwdsDAO.isEnabled).thenReturn(cwdsEnabled)
      when(cwdsDAO.getSupportedFormats).thenReturn(cwdsSupportedFormats)
      when(cwdsDAO.importV1(any[String], any[AsyncImportRequest])(any[UserInfo])).thenReturn(genericJob)
      when(importServiceDAO.importJob(any[String], any[String], any[AsyncImportRequest], any[Boolean])(any[UserInfo]))
        .thenReturn(Future.successful(RequestComplete(StatusCodes.Accepted, "")))
      when(rawlsDAO.getWorkspace(any[String], any[String])(any[UserInfo]))
        .thenReturn(Future.successful(workspaceResponse))

      // create input
      val input = AsyncImportRequest(url = "https://example.com", filetype = importFiletype)

      entityService.importJob("workspaceNamespace", "workspaceName", input, dummyUserInfo("token"))
        .futureValue // futureValue waits for the Future to complete

      val (cwdsCallCount, importServiceCallCount, rawlsCallCount) = if (expectUsingCwds) {
        // when sending imports to cWDS, we call cWDS and Rawls once, but never call Import Service
        (times(1), never, times(1))
      } else {
        // when sending imports to Import Service, we call Import Service once, but never call cWDS or Rawls
        (never, times(1), never)
      }

      verify(cwdsDAO, cwdsCallCount)
        .importV1(any[String], any[AsyncImportRequest])(any[UserInfo])
      verify(importServiceDAO, importServiceCallCount)
        .importJob(any[String], any[String], any[AsyncImportRequest], any[Boolean])(any[UserInfo])
      verify(rawlsDAO, rawlsCallCount)
        .getWorkspace(any[String], any[String])(any[UserInfo])
    }

  }

  "EntityService.getJob" - {

    val importServiceNotFound = new FireCloudExceptionWithErrorReport(
      ErrorReport(
        StatusCodes.NotFound,
        "Import Service unit test intentional error"))

    "should return correctly if job found in cWDS and not call Import Service" in {
      val jobId = UUID.randomUUID().toString

      val cwdsResponse = ImportServiceListResponse(jobId, "status1", "filename1", None)

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val importServiceDAO = mockito[MockImportServiceDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      when(cwdsDAO.getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(cwdsResponse)
      when(importServiceDAO.getJob(any[String], any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(Future.failed(importServiceNotFound))

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // get job via entity service
      val actual = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken")).futureValue

      actual shouldBe cwdsResponse
      verify(importServiceDAO, never).getJob(any[String], any[String], any[String])(any[UserInfo])
    }
    "should return correctly if job not found in cWDS, but is found in Import Service" in {
      val jobId = UUID.randomUUID().toString

      val importServiceResponse = ImportServiceListResponse(jobId, "status1", "filename1", None)

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val importServiceDAO = mockito[MockImportServiceDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      doThrow(new ApiException(404, "unit test intentional error"))
        .when(cwdsDAO).getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo])
      when(importServiceDAO.getJob(any[String], any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(Future.successful(importServiceResponse))

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // get job via entity service
      val actual = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken")).futureValue

      actual shouldBe importServiceResponse
    }
    "should return correctly if cWDS errors, but is found in Import Service" in {
      val jobId = UUID.randomUUID().toString

      val importServiceResponse = ImportServiceListResponse(jobId, "status1", "filename1", None)

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val importServiceDAO = mockito[MockImportServiceDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      doThrow(new ApiException(500, "unit test intentional error"))
        .when(cwdsDAO).getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo])
      when(importServiceDAO.getJob(any[String], any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(Future.successful(importServiceResponse))

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // get job via entity service
      val actual = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken")).futureValue

      actual shouldBe importServiceResponse
    }
    "should return 404 if job not found in either cWDS or Import Service" in {
      val jobId = UUID.randomUUID().toString

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val importServiceDAO = mockito[MockImportServiceDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      doThrow(new ApiException(404, "cWDS unit test intentional error"))
        .when(cwdsDAO).getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo])
      when(importServiceDAO.getJob(any[String], any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(Future.failed(importServiceNotFound))

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // get job via entity service
      val getJobFuture = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken"))

      ScalaFutures.whenReady(getJobFuture.failed) { actual =>
        actual shouldBe a [FireCloudExceptionWithErrorReport]
        val fex = actual.asInstanceOf[FireCloudExceptionWithErrorReport]
        fex.errorReport.statusCode should contain (StatusCodes.NotFound)
      }
    }
    "should return Import Service's error if job not found in cWDS and Import Service errors" in {
      val jobId = UUID.randomUUID().toString

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val importServiceDAO = mockito[MockImportServiceDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      doThrow(new ApiException(404, "cWDS unit test intentional error"))
        .when(cwdsDAO).getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo])
      when(importServiceDAO.getJob(any[String], any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(Future.failed(
          new FireCloudExceptionWithErrorReport(
            ErrorReport(
              StatusCodes.ImATeapot,
              "Import Service unit test intentional error"))
        ))

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // get job via entity service
      val getJobFuture = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken"))

      ScalaFutures.whenReady(getJobFuture.failed) { actual =>
        actual shouldBe a [FireCloudExceptionWithErrorReport]
        val fex = actual.asInstanceOf[FireCloudExceptionWithErrorReport]
        fex.errorReport.statusCode should contain (StatusCodes.ImATeapot)
        fex.errorReport.message shouldBe "Import Service unit test intentional error"
      }
    }
    "should return Import Service's error if cWDS errors and Import Service errors" in {
      val jobId = UUID.randomUUID().toString

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val importServiceDAO = mockito[MockImportServiceDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      doThrow(new ApiException(500, "cWDS unit test intentional error"))
        .when(cwdsDAO).getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo])
      when(importServiceDAO.getJob(any[String], any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(Future.failed(
          new FireCloudExceptionWithErrorReport(
            ErrorReport(
              StatusCodes.ImATeapot,
              "Import Service unit test intentional error"))
        ))

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO)

      // get job via entity service
      val getJobFuture = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken"))

      ScalaFutures.whenReady(getJobFuture.failed) { actual =>
        actual shouldBe a [FireCloudExceptionWithErrorReport]
        val fex = actual.asInstanceOf[FireCloudExceptionWithErrorReport]
        fex.errorReport.statusCode should contain (StatusCodes.ImATeapot)
        fex.errorReport.message shouldBe "Import Service unit test intentional error"
      }
    }
    "should not call cWDS or Rawls if cWDS is not enabled" in {
      val jobId = UUID.randomUUID().toString

      val importServiceResponse = ImportServiceListResponse(jobId, "status1", "filename1", None)

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val importServiceDAO = mockito[MockImportServiceDAO]
      val mockRawlsDAO = mockito[MockRawlsDAO]

      when(cwdsDAO.isEnabled).thenReturn(false)
      when(importServiceDAO.getJob(any[String], any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(Future.successful(importServiceResponse))

      // inject mocks to entity service
      val entityService = getEntityService(mockImportServiceDAO = importServiceDAO, cwdsDAO = cwdsDAO, rawlsDAO = mockRawlsDAO)

      // get job via entity service
      val actual = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken")).futureValue

      actual shouldBe importServiceResponse
      verify(cwdsDAO, never).getJobV1(any[String], any[String])(any[UserInfo])
      verify(mockRawlsDAO, never).getWorkspace(any[String], any[String])(any[UserInfo])
    }
  }

  private def getEntityService(mockGoogleServicesDAO: MockGoogleServicesDAO = new MockGoogleServicesDAO,
                               mockImportServiceDAO: MockImportServiceDAO = new MockImportServiceDAO,
                               rawlsDAO: MockRawlsDAO = new MockRawlsDAO,
                               cwdsDAO: MockCwdsDAO = new MockCwdsDAO(false)) = {
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
