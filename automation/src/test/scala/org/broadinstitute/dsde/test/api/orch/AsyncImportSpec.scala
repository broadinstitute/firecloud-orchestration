package org.broadinstitute.dsde.test.api.orch

import java.util.UUID
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import bio.terra.datarepo.api.RepositoryApi
import bio.terra.datarepo.client.ApiClient
import bio.terra.datarepo.model.JobModel.JobStatusEnum
import bio.terra.datarepo.model.{EnumerateSortByParam, SnapshotRetrieveIncludeModel, SqlSortDirection}
import org.broadinstitute.dsde.rawls.model.EntityTypeMetadata
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.EntityTypeMetadataFormat
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.ServiceTestConfig.FireCloud
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.parboiled.common.FileUtils
import org.scalatest.OptionValues._
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.json._

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.{Failure, Success, Try}

class AsyncImportSpec extends FreeSpec with Matchers with Eventually with ScalaFutures
  with BillingFixtures with WorkspaceFixtures with Orchestration {

  final implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(300, Seconds)), interval = scaled(Span(2, Seconds)))

  // this test.avro is copied from PyPFB's fixture at https://github.com/uc-cdis/pypfb/tree/master/tests/pfb-data
  private val testAvroFile = "https://storage.googleapis.com/fixtures-for-tests/fixtures/public/test.avro"

  lazy private val expectedEntities: Map[String, EntityTypeMetadata] = Source.fromResource("AsyncImportSpec-pfb-expected-entities.json").getLines().mkString
    .parseJson.convertTo[Map[String, EntityTypeMetadata]]

  "Orchestration" - {

    "should import a PFB file via import service" - {
      "for the owner of a workspace" in {
        val owner = UserPool.chooseProjectOwner
        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            val startTime = System.currentTimeMillis()

            // call importJob as owner
            val importJobId = startImportJob(projectName, workspaceName, testAvroFile, "pfb")(owner.makeAuthToken())

            // poll for completion as owner
            waitForImportJob(projectName, workspaceName, importJobId, startTime)(owner)

            // inspect data entities and confirm correct import as owner
            val actualMetadata = getEntityMetadata(projectName, workspaceName, importJobId)(owner.makeAuthToken())
            compareMetadata(actualMetadata, expectedEntities)
          }(owner.makeAuthToken())
        }
      }

      "for writers of a workspace" in {
        val owner = UserPool.chooseProjectOwner
        val writer = UserPool.chooseStudent

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("writer-pfb-import"), aclEntries = List(AclEntry(writer.email, WorkspaceAccessLevel.Writer))) { workspaceName =>
            val startTime = System.currentTimeMillis()

            // call importJob as writer
            val importJobId = startImportJob(projectName, workspaceName, testAvroFile, "pfb")(writer.makeAuthToken())

            // poll for completion as writer
            waitForImportJob(projectName, workspaceName, importJobId, startTime)(writer)

            // inspect data entities and confirm correct import as writer
            val actualMetadata = getEntityMetadata(projectName, workspaceName, importJobId)(writer.makeAuthToken())
            compareMetadata(actualMetadata, expectedEntities)
          } (owner.makeAuthToken())
        }
      }
    }

    "should asynchronously result in an error when attempting to import a PFB file via import service" - {
      "if the file to be imported is invalid" in {
        val owner = UserPool.chooseProjectOwner
        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            val startTime = System.currentTimeMillis()

            // call importPFB as owner
            val importJobId = startImportJob(projectName, workspaceName,
              "https://storage.googleapis.com/fixtures-for-tests/fixtures/this-intentionally-does-not-exist", "pfb")(owner.makeAuthToken())

            // poll for completion as owner
            waitForImportJob(projectName, workspaceName, importJobId, startTime, expectedStatus = "Error", failIfStatuses = List("Done"))(owner)
          }(owner.makeAuthToken())
        }
      }
    }

    "should synchronously return an error when attempting to import a PFB file via import service" - {

      "for readers of a workspace" in {
        val owner = UserPool.chooseProjectOwner
        val reader = UserPool.chooseStudent

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("reader-pfb-import"), aclEntries = List(AclEntry(reader.email, WorkspaceAccessLevel.Reader))) { workspaceName =>

            // call importPFB as reader
            val exception = intercept[RestException] {
              startImportJob(projectName, workspaceName, testAvroFile, "pfb")(reader.makeAuthToken())
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.Forbidden
            errorReport.message should include (s"Cannot perform the action write on $projectName/$workspaceName")

          } (owner.makeAuthToken())
        }
      }

      "with an invalid POST payload" in {
        val owner = UserPool.chooseProjectOwner
        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("reader-pfb-import")) { workspaceName =>

            // call importPFB with a payload of the wrong shape
            val exception = intercept[RestException] {
              Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", "this is a string, not json")(owner.makeAuthToken())
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.BadRequest
            errorReport.message should include (s"Object expected in field 'url'")

          } (owner.makeAuthToken())
        }
      }

    }

    "should import a TSV asynchronously for entities and entity membership" in {
      val owner = UserPool.chooseProjectOwner
      withCleanBillingProject(owner) { projectName =>
        withWorkspace(projectName, prependUUID("tsv-entities")) { workspaceName =>
          val participantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq()))
          val participantAndSetEntityMetadata = participantEntityMetadata + ("participant_set" -> EntityTypeMetadata(2, "participant_set_id", Seq("participants")))

          importAsync(projectName, workspaceName, "ADD_PARTICIPANTS.tsv", participantEntityMetadata)(owner)
          importAsync(projectName, workspaceName, "MEMBERSHIP_PARTICIPANT_SET.tsv", participantAndSetEntityMetadata)(owner)
        }(owner.makeAuthToken())
      }
    }

    "should import a TSV asynchronously for updates" in {
      val owner = UserPool.chooseProjectOwner
      withCleanBillingProject(owner) { projectName =>
        withWorkspace(projectName, prependUUID("tsv-updates")) { workspaceName =>
          val participantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq()))
          val updatedParticipantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq("age")))

          importAsync(projectName, workspaceName, "ADD_PARTICIPANTS.tsv", participantEntityMetadata)(owner)
          importAsync(projectName, workspaceName, "UPDATE_PARTICIPANTS.tsv", updatedParticipantEntityMetadata)(owner)
        }(owner.makeAuthToken())
      }

    }

    // N.B. there are also snapshot-import-by-reference integration tests in Rawls,
    // this spec only addresses snapshot-import-by-copy
    "should import-by-copy a Data Repo export asynchronously" - {
      "for the owner of a workspace" in {
        val workspaceOwner = UserPool.chooseProjectOwner
        val additionalOwner = UserPool.userConfig.Owners.getUserCredential("hermione")

        val (snapshotManifest, snapshotTableNames) = withClue("Unexpected problem while exporting snapshot from TDR: ") {
          exportTdrSnapshot()(additionalOwner)
        }

        val additionalOwnerAcl = AclEntry(additionalOwner.email, WorkspaceAccessLevel.Owner)

        withCleanBillingProject(workspaceOwner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-tdr-by-copy"), aclEntries = List(additionalOwnerAcl)) { workspaceName =>
            val startTime = System.currentTimeMillis()

            // verify that entity metadata for this workspace is empty
            val metadataBefore = getEntityMetadata(projectName, workspaceName, "(job not started yet)")(additionalOwner.makeAuthToken())
            withClue("before snapshot import-by-copy, no data tables should exist") {
              metadataBefore shouldBe empty
            }

            // start async import of manifest file into a new workspace, response will be a jobid
            val importJobId = startImportJob(projectName, workspaceName, snapshotManifest, "tdrexport")(additionalOwner.makeAuthToken())

            // poll for completion as owner
            waitForImportJob(projectName, workspaceName, importJobId, startTime)(additionalOwner)

            // retrieve entity metadata for workspace, should have same types as snapshot tables
            val metadataAfter = getEntityMetadata(projectName, workspaceName, importJobId)(additionalOwner.makeAuthToken())
            withClue("after snapshot import-by-copy, should have the same data tables as TDR") {
              metadataAfter.keySet should contain theSameElementsAs snapshotTableNames
            }
          }(workspaceOwner.makeAuthToken())
        }
      }
      "for a writer with can-share" in {
        // hermione has access to snapshots, so in this test we use a student as the workspace owner,
        // and hermione as the writer
        val workspaceOwner = UserPool.chooseProjectOwner
        val writer = UserPool.userConfig.Owners.getUserCredential("hermione")

        val (snapshotManifest, snapshotTableNames) = withClue("Unexpected problem while exporting snapshot from TDR: ") {
          exportTdrSnapshot()(writer)
        }

        val writerWithCanShareAcl = AclEntry(writer.email, WorkspaceAccessLevel.Writer, canShare = Option(true))

        withCleanBillingProject(workspaceOwner) { projectName =>
          withWorkspace(projectName, prependUUID("writer-tdr-by-copy"), aclEntries = List(writerWithCanShareAcl)) { workspaceName =>
            val startTime = System.currentTimeMillis()

            // verify that entity metadata for this workspace is empty
            val metadataBefore = getEntityMetadata(projectName, workspaceName, "(job not started yet)")(writer.makeAuthToken())
            withClue("before snapshot import-by-copy, no data tables should exist") {
              metadataBefore shouldBe empty
            }

            // start async import of manifest file into a new workspace, response will be a jobid
            val importJobId = startImportJob(projectName, workspaceName, snapshotManifest, "tdrexport")(writer.makeAuthToken())

            // poll for completion as owner
            waitForImportJob(projectName, workspaceName, importJobId, startTime)(writer)

            // retrieve entity metadata for workspace, should have same types as snapshot tables
            val metadataAfter = getEntityMetadata(projectName, workspaceName, importJobId)(writer.makeAuthToken())
            withClue("after snapshot import-by-copy, should have the same data tables as TDR") {
              metadataAfter.keySet should contain theSameElementsAs snapshotTableNames
            }
          } (workspaceOwner.makeAuthToken())
        }
      }
    }

    "should asynchronously result in an error on Data Repo import-by-copy" - {
      "for a nonexistent manifest file" in {
        val owner = UserPool.chooseProjectOwner

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("tdr-by-copy-404")) { workspaceName =>
            val startTime = System.currentTimeMillis()

            // call importJob as owner
            val importJobId = startImportJob(projectName, workspaceName,
              "gs://fixtures-for-tests/fixtures/this-intentionally-does-not-exist", "tdrexport")(owner.makeAuthToken())

            // poll for completion as owner
            waitForImportJob(projectName, workspaceName, importJobId, startTime, expectedStatus = "Error", failIfStatuses = List("Done"))(owner)
          }(owner.makeAuthToken())
        }
      }
    }

    "should throw a synchronous error on Data Repo import-by-copy" - {
      "for a writer without can-share" ignore {
        // hermione has access to snapshots, so in this test we use a student as the workspace owner,
        // and hermione as the writer
        val workspaceOwner = UserPool.chooseProjectOwner
        val writer = UserPool.userConfig.Owners.getUserCredential("hermione")

        val writerNoCanShareAcl = AclEntry(writer.email, WorkspaceAccessLevel.Writer, canShare = Option(false))

        withCleanBillingProject(workspaceOwner) { projectName =>
          withWorkspace(projectName, prependUUID("noshare-tdr-by-copy"), aclEntries = List(writerNoCanShareAcl)) { workspaceName =>
            // call importJob as writer, without can-share
            val exception = intercept[RestException] {
              // a bit of a hack - we ask to import the testAvroFile but specify "tdrexport" filetype.
              // this allows the test to skip the work of exporting a snapshot in TDR, which adds time.
              // Import Service should check the user's permission on the workspace and fail before even
              // reading the input file, so this should be ok ... if Import Service gets to the point of
              // trying to read testAvroFile, it'll throw a different error than what we're asserting on.
              startImportJob(projectName, workspaceName, testAvroFile, "tdrexport")(writer.makeAuthToken())
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.Forbidden
            errorReport.message should include (s"Cannot perform the action read_policies on $projectName/$workspaceName")

          } (workspaceOwner.makeAuthToken())
        }
      }
      "for a reader" in {
        val workspaceOwner = UserPool.chooseProjectOwner
        val reader = UserPool.chooseStudent

        withCleanBillingProject(workspaceOwner) { projectName =>
          withWorkspace(projectName, prependUUID("reader-tdr-by-copy"), aclEntries = List(AclEntry(reader.email, WorkspaceAccessLevel.Reader))) { workspaceName =>

            // call importJob as reader
            val exception = intercept[RestException] {
              // a bit of a hack - we ask to import the testAvroFile but specify "tdrexport" filetype.
              // this allows the test to skip the work of exporting a snapshot in TDR, which adds time.
              // Import Service should check the user's permission on the workspace and fail before even
              // reading the input file, so this should be ok ... if Import Service gets to the point of
              // trying to read testAvroFile, it'll throw a different error than what we're asserting on.
              startImportJob(projectName, workspaceName, testAvroFile, "tdrexport")(reader.makeAuthToken())
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.Forbidden
            errorReport.message should include (s"Cannot perform the action write on $projectName/$workspaceName")

          } (workspaceOwner.makeAuthToken())
        }

      }
    }
  }

  /** find a snapshot in TDR, describe its tables, and export it.
    * returns a tuple of (export manifest file, Set(non-empty table names in snapshot)
    * */
  private def exportTdrSnapshot()(implicit credentials: Credentials): (String, Set[String]) = {
    val dataRepoBaseUrl = FireCloud.dataRepoApiUrl

    val apiClient: ApiClient = new ApiClient()
    apiClient.setBasePath(dataRepoBaseUrl)
    apiClient.setAccessToken(credentials.makeAuthToken().value)

    val dataRepoApi = new RepositoryApi(apiClient)

    val numSnapshots = 1

    // list and choose an available snapshot in TDR
    logger.info(s"calling data repo at $dataRepoBaseUrl as user ${credentials.email} ... ")
    val drSnapshots = Try(dataRepoApi.enumerateSnapshots(
      0, numSnapshots, EnumerateSortByParam.CREATED_DATE, SqlSortDirection.DESC, "", "", java.util.Collections.emptyList() )) match {
      case Success(s) => s
      case Failure(ex) =>
        logger.error(s"data repo call as user ${credentials.email} failed: ${ex.getMessage}", ex)
        throw ex
    }
    assume(drSnapshots.getItems.size() == numSnapshots,
      s"---> TDR at $dataRepoBaseUrl did not have $numSnapshots snapshots for this test to use!" +
        s" This is likely a problem in environment setup, but has a chance of being a problem in runtime code. <---")

    logger.info(s"found ${drSnapshots.getItems.size()} snapshot(s) from $dataRepoBaseUrl as user ${credentials.email}: " +
      s"${drSnapshots.getItems.asScala.map(_.getId).mkString(", ")}")

    val snapshotFromList = drSnapshots.getItems.asScala.head

    // describe tables for the snapshot in TDR
    val snapshotModel = dataRepoApi.retrieveSnapshot(snapshotFromList.getId, List(SnapshotRetrieveIncludeModel.TABLES).asJava)
    // collect the non-empty tables
    val snapshotTableNames: Set[String] = snapshotModel.getTables.asScala.collect {
      case table if table.getRowCount > 0 => table.getName
    }.toSet

    assume(snapshotTableNames.nonEmpty, "TDR snapshot should have at least 1 populated table!")

    // start export for the snapshot, response will be a jobid
    val exportJob = dataRepoApi.exportSnapshot(snapshotModel.getId)

    // poll jobid for export completion
    withClue("while polling for Data Repo snapshot export completion, an error occurred: ") {
      eventually {
        val currentJobStatus = dataRepoApi.retrieveJob(exportJob.getId)
        val statusValue = currentJobStatus.getJobStatus
        statusValue shouldNot be (JobStatusEnum.RUNNING)
        if (statusValue == JobStatusEnum.FAILED) {
          fail(s"Data Repo snapshot export failed for snapshotId ${snapshotModel.getId} and " +
            s"jobId ${exportJob.getId}. Last status was: ${currentJobStatus.toString}")
        }
      }
    }

    // these classes are VERY non-comprehensive representations of SnapshotExportResponseModel,
    // but they're all we need to extract the manifest value out of the response.
    case class Parquet(manifest: String)
    case class Format(parquet: Parquet)
    case class ExportModel(format: Format)
    implicit val parquetJsonFormat: RootJsonFormat[Parquet] = jsonFormat1(Parquet)
    implicit val formatFormat: RootJsonFormat[Format] = jsonFormat1(Format)
    implicit val exportModelFormat: RootJsonFormat[ExportModel] = jsonFormat1(ExportModel)

    // on export completion, get job result. The data repo client, via retrieveJobResult(), returns a LinkedHashMap
    // and is not strongly typed. So, we get the response as a string and parse it into our hacky case classes.
    val jobResultHttpResponse = Orchestration.getRequest(s"${dataRepoBaseUrl}api/repository/v1/jobs/${exportJob.getId}/result")(credentials.makeAuthToken())
    val jobResultStringResponse = blockForStringBody(jobResultHttpResponse)
    logger.info(s"Data Repo snapshot export job result for snapshotId ${snapshotModel.getId} and " +
      s"jobId ${exportJob.getId} is: $jobResultStringResponse")
    val exportModel = jobResultStringResponse.parseJson.convertTo[ExportModel]
    val snapshotManifest = exportModel.format.parquet.manifest

    (snapshotManifest, snapshotTableNames)
  }

  private def extractJobId(responseAsString: String): String = {
    // expect to get exactly one jobId back
    val importJobIdValues: Seq[JsValue] = responseAsString.parseJson.asJsObject.getFields("jobId")
    importJobIdValues should have size 1

    importJobIdValues.head match {
      case js:JsString => js.value
      case x => fail("got an invalid jobId: " + x.toString())
    }
  }

  /** create an import job and return the job id */
  private def startImportJob(projectName: String, workspaceName: String, url: String, filetype: String)(implicit authToken: AuthToken): String = {
    val payload = Map("url" -> url, "filetype" -> filetype)
    // call importJob
    val postResponse: String = Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importJob", payload)
    extractJobId(postResponse)
  }

  /** poll for an import job to be complete */
  private def waitForImportJob(projectName: String, workspaceName: String, importJobId: String,
                               startTime: Long,
                               expectedStatus: String = "Done",
                               failIfStatuses: List[String] = List("Error"))
                              (implicit creds: Credentials) = {
    // for json deserialization
    case class ImportJobResponse(status: String, message: Option[String])
    implicit val importJobResponseFormat: RootJsonFormat[ImportJobResponse] = jsonFormat2(ImportJobResponse)

    // poll for completion
    withClue(s"import job $importJobId failed its eventually assertions on job status: ") {
      eventually(timeout = Timeout(scaled(Span(10, Minutes))), interval = Interval(scaled(Span(5, Seconds)))) {
        val requestId = scala.util.Random.alphanumeric.take(8).mkString // just to assist with logging
        logger.info(s"[$requestId] About to check status for import job $importJobId. Elapsed: ${humanReadableMillis(System.currentTimeMillis()-startTime)}")
        val resp: HttpResponse = Orchestration.getRequest(s"${workspaceUrl(projectName, workspaceName)}/importJob/$importJobId")(creds.makeAuthToken())
        val respStatus = resp.status
        logger.info(s"[$requestId] HTTP response status for import job $importJobId status request is [${respStatus.intValue()}]. Elapsed: ${humanReadableMillis(System.currentTimeMillis()-startTime)}")
        respStatus shouldBe StatusCodes.OK
        val importResponse = blockForStringBody(resp).parseJson.convertTo[ImportJobResponse]
        val importStatus = importResponse.status
        logger.info(s"[$requestId] Import Service job status for import job $importJobId is [$importStatus]")
        // checking for fail conditions allows the test to exit earlier than the eventually{} timeout if we detect a problem
        if (failIfStatuses.contains(importStatus)) {
          fail(s"Import job $importJobId with message [${importResponse.message}] should not be in status $importStatus")
        }
        importStatus shouldBe expectedStatus

      }
    }

  }

  /** retrieve entity type metadata for a given workspace */
  private def getEntityMetadata(projectName: String, workspaceName: String, importJobId: String)(implicit authToken: AuthToken): Map[String, EntityTypeMetadata] = {
    val startTime = System.currentTimeMillis()
    val resp = Orchestration.getRequest( s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$workspaceName/entities")
    val respStatus = resp.status
    logger.info(s"HTTP response status for import job $importJobId metadata request is [${respStatus.intValue()}]. Duration of metadata request: ${humanReadableMillis(System.currentTimeMillis()-startTime)}")
    respStatus shouldBe StatusCodes.OK
    blockForStringBody(resp).parseJson.convertTo[Map[String, EntityTypeMetadata]]
  }

  private def importAsync(projectName: String, workspaceName: String, importFilePath: String, expectedResult: Map[String, EntityTypeMetadata])(implicit creds: Credentials): Unit = {
    val startTime = System.currentTimeMillis()
    val importFileString = FileUtils.readAllTextFromResource(importFilePath)

    // call import as owner
    val postResponse: String = Orchestration.importMetaDataFlexible(projectName, workspaceName, isAsync = true, "entities", importFileString)(creds.makeAuthToken())
    // expect to get exactly one jobId back
    val importJobId: String = extractJobId(postResponse)

    logger.info(s"$projectName/$workspaceName has import job $importJobId for file $importFilePath")

    // poll for completion
    waitForImportJob(projectName, workspaceName, importJobId, startTime)

    logger.info(s"$projectName/$workspaceName import job $importJobId completed for file $importFilePath in " +
      s"${humanReadableMillis(System.currentTimeMillis()-startTime)}. Now checking metadata ... ")

    // inspect data entities and confirm correct import as owner. With the import complete, the metadata should already
    // be correct, so we don't need to use eventually here.
    withClue(s"import job $importJobId failed its eventually assertion on entity metadata: ") {
      val result = getEntityMetadata(projectName, workspaceName, importJobId)(creds.makeAuthToken())
      compareMetadata(result, expectedResult)
    }

    logger.info(s"$projectName/$workspaceName import job $importJobId metadata check for file $importFilePath passed in " +
      s"${humanReadableMillis(System.currentTimeMillis()-startTime)}. importAsync() complete.")

  }

  private def humanReadableMillis(millis: Long) = {
    val mins: java.lang.Long = TimeUnit.MILLISECONDS.toMinutes(millis)
    val secs: java.lang.Long = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(mins) // the remainder of seconds
    String.format("%d minutes, %d sec", mins, secs)
  }

  private def prependUUID(suffix: String): String = s"${UUID.randomUUID().toString}-$suffix"

  private def blockForStringBody(response: HttpResponse): String = {
    Try(Unmarshal(response.entity).to[String].futureValue(Timeout(scaled(Span(60, Seconds))))) match {
      case Success(s) => s
      case Failure(ex) =>
        logger.error(s"blockForStringBody failed with ${ex.getMessage}", ex)
        throw ex
    }
  }

  private def workspaceUrl(projectName: String, wsName: String): String =
    s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$wsName"

  private def compareMetadata(left: Map[String, EntityTypeMetadata], right: Map[String, EntityTypeMetadata]): Unit = {
    // we don't care about ordering of keys
    left.keys should contain theSameElementsAs right.keys

    left.foreach {
      case (entityType: String, leftMetadata: EntityTypeMetadata) =>
        val rightMetadata = right(entityType)
        leftMetadata.idName shouldBe rightMetadata.idName
        leftMetadata.count shouldBe rightMetadata.count
        // we don't care about ordering of attribute names
        leftMetadata.attributeNames should contain theSameElementsAs rightMetadata.attributeNames
    }
  }

}
