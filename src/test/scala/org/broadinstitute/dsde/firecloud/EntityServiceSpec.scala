package org.broadinstitute.dsde.firecloud

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpResponse, StatusCode, StatusCodes}
import com.google.cloud.storage.StorageException
import org.broadinstitute.dsde.firecloud.dataaccess.LegacyFileTypes.FILETYPE_RAWLS

import org.broadinstitute.dsde.firecloud.dataaccess.{MockCwdsDAO, MockRawlsDAO}
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.{AsyncImportRequest, AsyncImportResponse, EntityUpdateDefinition, FirecloudModelSchema, ImportOptions, ImportServiceListResponse, ImportServiceResponse, ModelSchema, RequestCompleteWithErrorReport, UserInfo, WithAccessToken}
import org.broadinstitute.dsde.firecloud.service.PerRequest.RequestComplete
import org.broadinstitute.dsde.firecloud.service.{BaseServiceSpec, PerRequest}
import org.broadinstitute.dsde.rawls.model.{ErrorReport, ErrorReportSource, WorkspaceName}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GcsObjectName, GcsPath}
import org.databiosphere.workspacedata.client.ApiException
import org.databiosphere.workspacedata.model.GenericJob
import org.databiosphere.workspacedata.model.GenericJob.StatusEnum
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doThrow, never, times, verify, when}
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.parboiled.common.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.{mock => mockito}

import java.util.UUID
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
        s"should return Accepted with an import jobId for (async=true + $tsvType TSV)" in {
          val testCwdsDao = new SuccessfulCwdsDAO
          val entityService = getEntityService(cwdsDAO = testCwdsDao)
          val response =
            entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
              tsvData, userToken, isAsync = true).futureValue

          val rqResponse = response.asInstanceOf[RequestComplete[(StatusCode, AsyncImportResponse)]]
          val expectedUriPrefix = "gs://cwds-testconf-bucketname/to-cwds/" + MockRawlsDAO.mockWorkspaceId + "/"
          rqResponse.response match {
            case (status, asyncImportResponse) =>
              status shouldBe StatusCodes.Accepted
              asyncImportResponse.jobId shouldNot be(empty)
              asyncImportResponse.url should startWith(expectedUriPrefix)
              asyncImportResponse.workspace shouldEqual WorkspaceName("workspaceNamespace", "workspaceName")
          }
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

        s"should send $expectedEntityType tsv to cWDS with appropriate options" in {
            // set up mocks
            val cwdsDAO = mockito[MockCwdsDAO]
            val rawlsDAO = mockito[MockRawlsDAO]

            // inject mocks to entity service
            val entityService = getEntityService(cwdsDAO = cwdsDAO, rawlsDAO = rawlsDAO)

            // set up behaviors
            val genericJob: GenericJob = new GenericJob
            genericJob.setJobId(UUID.randomUUID())
            // the "new MockRawlsDAO()" here is only to get access to a pre-canned WorkspaceResponse object
            val workspaceResponse = new MockRawlsDAO().rawlsWorkspaceResponseWithAttributes

            when(cwdsDAO.isEnabled).thenReturn(true)
            when(cwdsDAO.getSupportedFormats).thenReturn(List("pfb","tdrexport", "rawlsjson"))
            when(rawlsDAO.getWorkspace(any[String], any[String])(any[UserInfo]))
              .thenReturn(Future.successful(workspaceResponse))

            entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName", tsvData, dummyUserInfo("token"), true).futureValue

          val argumentCaptor = ArgumentCaptor.forClass(classTag[AsyncImportRequest].runtimeClass).asInstanceOf[ArgumentCaptor[AsyncImportRequest]]

          verify(cwdsDAO, times(1))
            .importV1(any[String], argumentCaptor.capture())(any[UserInfo])
          val capturedRequest = argumentCaptor.getValue
          capturedRequest.options should be(Some(ImportOptions(None, Some(tsvType != "update"))))
          verify(rawlsDAO, times(1))
              .getWorkspace(any[String], any[String])(any[UserInfo])

          }
    }

    "should return error for (async=true) when failed to write to GCS" in {
      val testGoogleDAO = new ErroringGoogleServicesDAO
      val entityService = getEntityService(mockGoogleServicesDAO = testGoogleDAO)
      val response =
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken, isAsync = true).futureValue

      val errorResponse = response.asInstanceOf[RequestComplete[(StatusCode, ErrorReport)]]

      errorResponse.response match {
        case (status, errorReport) =>
          status shouldEqual StatusCodes.InternalServerError
          errorReport.message should be("Unexpected error during async TSV import")
      }
    }

    "should return error for (async=true) when import service returns sync error" in {
      val testCwdsDao = new ErroringCwdsDao
      val entityService = getEntityService(cwdsDAO = testCwdsDao)
      val response =
        entityService.importEntitiesFromTSV("workspaceNamespace", "workspaceName",
          tsvParticipants, userToken, isAsync = true).futureValue

      val errorResponse = response.asInstanceOf[RequestComplete[(StatusCode, ErrorReport)]]

      errorResponse.response match {
        case (status, errorReport) =>
          status shouldEqual StatusCodes.InternalServerError
          errorReport.message should be("Unexpected error during async TSV import")
      }
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
    "should return empty list if cWDS returns empty list" in {
      val cwdsResponse = List()

      listJobsTestImpl(cwdsResponse)
    }

    "should return cWDS results" in {
      val importServiceResponse = List()
      val cwdsResponse = List(
        ImportServiceListResponse("jobId1", "status1", "filetype1", None),
        ImportServiceListResponse("jobId2", "status2", "filetype2", None)
      )

      listJobsTestImpl(cwdsResponse)
    }

    def listJobsTestImpl(cwdsResponse: List[ImportServiceListResponse]) = {
      // set up mock
      val cwdsDAO = mockito[MockCwdsDAO]

      when(cwdsDAO.listJobsV1(any[String], any[Boolean])(any[UserInfo])).thenReturn(cwdsResponse)
      when(cwdsDAO.isEnabled).thenReturn(true)

      // inject mocks to entity service
      val entityService = getEntityService(cwdsDAO = cwdsDAO)

      // list jobs via entity service
      val actual = entityService.listJobs("workspaceNamespace", "workspaceName", runningOnly = true, dummyUserInfo("mytoken")).futureValue

      actual should contain theSameElementsAs cwdsResponse
    }
  }

  "EntityService.importJob" - {
    "should send tdrexport to cWDS" in {
      importJobTestImpl(importFiletype = "tdrexport")
    }
    "should send pfb to cWDS" in {
      importJobTestImpl(importFiletype = "pfb")
    }
    "should send rawlsjson to cWDS" in {
      importJobTestImpl(importFiletype = "rawlsjson")
    }

    def importJobTestImpl(importFiletype: String) = {
      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]
      val rawlsDAO = mockito[MockRawlsDAO]

      // inject mocks to entity service
      val entityService = getEntityService(cwdsDAO = cwdsDAO, rawlsDAO = rawlsDAO)

      // set up behaviors
      val genericJob: GenericJob = new GenericJob
      genericJob.setJobId(UUID.randomUUID())
      // the "new MockRawlsDAO()" here is only to get access to a pre-canned WorkspaceResponse object
      val workspaceResponse = new MockRawlsDAO().rawlsWorkspaceResponseWithAttributes

      when(cwdsDAO.importV1(any[String], any[AsyncImportRequest])(any[UserInfo])).thenReturn(genericJob)
      when(rawlsDAO.getWorkspace(any[String], any[String])(any[UserInfo]))
        .thenReturn(Future.successful(workspaceResponse))

      // create input
      val input = AsyncImportRequest(url = "https://example.com", filetype = importFiletype)

      entityService.importJob("workspaceNamespace", "workspaceName", input, dummyUserInfo("token"))
        .futureValue // futureValue waits for the Future to complete
      verify(cwdsDao, times(1))
        .importV1(any[String], any[AsyncImportRequest])(any[UserInfo])
    }

  }

  "EntityService.getJob" - {
    "should return correctly if job found in cWDS" in {
      val jobId = UUID.randomUUID().toString

      val cwdsResponse = ImportServiceListResponse(jobId, "status1", "filename1", None)

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      when(cwdsDAO.getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo]))
        .thenReturn(cwdsResponse)

      // inject mocks to entity service
      val entityService = getEntityService(cwdsDAO = cwdsDAO)

      // get job via entity service
      val actual = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken")).futureValue

      actual shouldBe cwdsResponse
    }
    "should return 404 if job not found cWDS" in {
      val jobId = UUID.randomUUID().toString

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      doThrow(new ApiException(404, "cWDS unit test intentional error"))
        .when(cwdsDAO).getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo])

      // inject mocks to entity service
      val entityService = getEntityService(cwdsDAO = cwdsDAO)

      // get job via entity service
      val getJobFuture = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken"))

      ScalaFutures.whenReady(getJobFuture.failed) { actual =>
        actual shouldBe a[ApiException]
        val apiEx = actual.asInstanceOf[ApiException]
        apiEx.getCode shouldEqual StatusCodes.NotFound.intValue
      }
    }
    "should escalate cWDS's error" in {
      val jobId = UUID.randomUUID().toString

      // set up mocks
      val cwdsDAO = mockito[MockCwdsDAO]

      when(cwdsDAO.isEnabled).thenReturn(true)
      doThrow(new ApiException(StatusCodes.ImATeapot.intValue, "cWDS unit test intentional error"))
        .when(cwdsDAO).getJobV1(any[String], ArgumentMatchers.eq(jobId))(any[UserInfo])

      // inject mocks to entity service
      val entityService = getEntityService(cwdsDAO = cwdsDAO)

      // get job via entity service
      val getJobFuture = entityService.getJob("workspaceNamespace", "workspaceName", jobId, dummyUserInfo("mytoken"))

      ScalaFutures.whenReady(getJobFuture.failed) { actual =>
        actual shouldBe a[ApiException]
        val apiEx = actual.asInstanceOf[ApiException]
        apiEx.getCode shouldBe StatusCodes.ImATeapot.intValue
        apiEx.getMessage should (include("cWDS unit test intentional error"))
      }
    }
  }

  private def getEntityService(mockGoogleServicesDAO: MockGoogleServicesDAO = new MockGoogleServicesDAO,
                               rawlsDAO: MockRawlsDAO = new MockRawlsDAO,
                               cwdsDAO: MockCwdsDAO = new MockCwdsDAO(false)) = {
    val application = app.copy(googleServicesDAO = mockGoogleServicesDAO, rawlsDAO = rawlsDAO, cwdsDAO = cwdsDAO)
    implicit val modelSchema: ModelSchema = FirecloudModelSchema
    EntityService.constructor(application)(modelSchema)(global)
  }

  class ErroringGoogleServicesDAO extends MockGoogleServicesDAO {
    def errorDefinition: Exception = new StorageException(418, "intentional unit test failure")

    override def writeObjectAsRawlsSA(bucketName: GcsBucketName, objectKey: GcsObjectName, objectContents: Array[Byte]): GcsPath =
    // throw a 418 so unit tests have an easy way to distinguish this error vs an error somewhere else in the stack
    throw errorDefinition
  }

  class SuccessfulCwdsDAO extends MockCwdsDAO {
    def successDefinition: GenericJob = {
      val genericJob: GenericJob = new GenericJob
      genericJob.setJobId(UUID.randomUUID())
      genericJob.status(StatusEnum.RUNNING)
      genericJob
    }
  }

  class ErroringCwdsDao extends MockCwdsDAO {

    // return a 429 so unit tests have an easy way to distinguish this error vs an error somewhere else in the stack
    def errorDefinition: GenericJob = throw new ApiException(StatusCodes.TooManyRequests.intValue, "intentional ErroringCwdsDao error")

    override def importV1(workspaceId: String, importRequest: AsyncImportRequest)(implicit userInfo: UserInfo): GenericJob = {
      importRequest.filetype match {
        case FILETYPE_RAWLS => errorDefinition
        case _ => ???
      }
    }
  }

}
