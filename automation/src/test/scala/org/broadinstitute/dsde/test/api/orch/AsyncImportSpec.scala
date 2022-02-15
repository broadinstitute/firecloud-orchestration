package org.broadinstitute.dsde.test.api.orch

import java.util.UUID
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import bio.terra.datarepo.api.RepositoryApi
import bio.terra.datarepo.client.ApiClient
import bio.terra.datarepo.model.JobModel.JobStatusEnum
import bio.terra.datarepo.model.{SnapshotExportResponseModel, SnapshotRetrieveIncludeModel}
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

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()

  final implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(300, Seconds)), interval = scaled(Span(2, Seconds)))

  // this test.avro is copied from PyPFB's fixture at https://github.com/uc-cdis/pypfb/tree/master/tests/pfb-data
  private val testPayload = Map("url" -> "https://storage.googleapis.com/fixtures-for-tests/fixtures/public/test.avro")

  lazy private val expectedEntities: Map[String, EntityTypeMetadata] = Source.fromResource("AsyncImportSpec-pfb-expected-entities.json").getLines().mkString
    .parseJson.convertTo[Map[String, EntityTypeMetadata]]

  "Orchestration" - {

    "should import a PFB file via import service" - {
      "for the owner of a workspace" in {
        implicit val token: AuthToken = ownerAuthToken

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            // call importPFB as owner
            val postResponse: String = Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", testPayload)
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1

            val importJobId: String = importJobIdValues.head match {
              case js:JsString => js.value
              case x => fail("got an invalid jobId: " + x.toString())
            }

            // poll for completion as owner
            eventually {
              val resp: HttpResponse = Orchestration.getRequest( s"${workspaceUrl(projectName, workspaceName)}/importPFB/$importJobId")
              resp.status shouldBe StatusCodes.OK
              blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value shouldBe JsString("Done")
            }

            // inspect data entities and confirm correct import as owner
            eventually {
              val resp = Orchestration.getRequest( s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$workspaceName/entities")
              compareMetadata(blockForStringBody(resp).parseJson.convertTo[Map[String, EntityTypeMetadata]], expectedEntities)
            }

          }
        }
      }

      "for writers of a workspace" in {
        val writer = UserPool.chooseStudent
        val writerToken = writer.makeAuthToken()

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("writer-pfb-import"), aclEntries = List(AclEntry(writer.email, WorkspaceAccessLevel.Writer))) { workspaceName =>
            // call importPFB as writer
            val postResponse: String = Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", testPayload)(writerToken)
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1
            val importJobId: String = importJobIdValues.head match {
              case js:JsString => js.value
              case x => fail("got an invalid jobId: " + x.toString())
            }

            // poll for completion as writer
            eventually {
              val resp = Orchestration.getRequest( s"${workspaceUrl(projectName, workspaceName)}/importPFB/$importJobId")(writerToken)
              blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value shouldBe JsString("Done")
            }

            // inspect data entities and confirm correct import as writer
            eventually {
              val resp = Orchestration.getRequest( s"${workspaceUrl(projectName, workspaceName)}/entities")(writerToken)
              compareMetadata(blockForStringBody(resp).parseJson.convertTo[Map[String, EntityTypeMetadata]], expectedEntities)
            }

          } (ownerAuthToken)
        }
      }
    }

    "should asynchronously result in an error when attempting to import a PFB file via import service" - {
      "if the file to be imported is invalid" in {
        implicit val token: AuthToken = ownerAuthToken

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            // call importPFB as owner
            val postResponse: String = Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB",
              Map("url" -> "https://storage.googleapis.com/fixtures-for-tests/fixtures/this-intentionally-does-not-exist"))
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1

            val importJobId: String = importJobIdValues.head match {
              case js:JsString => js.value
              case x => fail("got an invalid jobId: " + x.toString())
            }

            // poll for completion as owner
            eventually {
              val resp: HttpResponse = Orchestration.getRequest( s"${workspaceUrl(projectName, workspaceName)}/importPFB/$importJobId")
              resp.status shouldBe StatusCodes.OK
              blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value shouldBe JsString("Error")
            }
          }
        }
      }
    }

    "should synchronously return an error when attempting to import a PFB file via import service" - {

      "for readers of a workspace" in {
        val reader = UserPool.chooseStudent

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("reader-pfb-import"), aclEntries = List(AclEntry(reader.email, WorkspaceAccessLevel.Reader))) { workspaceName =>

            // call importPFB as reader
            val exception = intercept[RestException] {
              Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", testPayload)(reader.makeAuthToken())
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.Forbidden
            errorReport.message should include (s"Cannot perform the action write on $projectName/$workspaceName")

          } (ownerAuthToken)
        }
      }

      "with an invalid POST payload" in {
        implicit val token: AuthToken = ownerAuthToken
        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("reader-pfb-import")) { workspaceName =>

            // call importPFB with a payload of the wrong shape
            val exception = intercept[RestException] {
              Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", "this is a string, not json")
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.BadRequest
            errorReport.message should include (s"Object expected in field 'url'")

          } (ownerAuthToken)
        }
      }

    }

    "should import a TSV asynchronously for entities and entity membership" in {
      implicit val token: AuthToken = ownerAuthToken
      withCleanBillingProject(owner) { projectName =>
        withWorkspace(projectName, prependUUID("tsv-entities")) { workspaceName =>
          val participantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq()))
          val participantAndSetEntityMetadata = participantEntityMetadata + ("participant_set" -> EntityTypeMetadata(2, "participant_set_id", Seq("participants")))

          importAsync(projectName, workspaceName, "ADD_PARTICIPANTS.tsv", participantEntityMetadata)
          importAsync(projectName, workspaceName, "MEMBERSHIP_PARTICIPANT_SET.tsv", participantAndSetEntityMetadata)
        }
      }
    }

    "should import a TSV asynchronously for updates" in {
      implicit val token: AuthToken = ownerAuthToken
      withCleanBillingProject(owner) { projectName =>
        withWorkspace(projectName, prependUUID("tsv-updates")) { workspaceName =>
          val participantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq()))
          val updatedParticipantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq("age")))

          importAsync(projectName, workspaceName, "ADD_PARTICIPANTS.tsv", participantEntityMetadata)
          importAsync(projectName, workspaceName, "UPDATE_PARTICIPANTS.tsv", updatedParticipantEntityMetadata)
        }
      }

    }

    // N.B. there are also snapshot-import-by-reference integration tests in Rawls,
    // this test only addresses snapshot-import-by-copy
    "should import a Data Repo export asynchronously" - {
      "for the owner of a workspace" in {
        val owner = UserPool.userConfig.Owners.getUserCredential("hermione")

        implicit val ownerAuthToken: AuthToken = owner.makeAuthToken()

        val dataRepoBaseUrl = FireCloud.dataRepoApiUrl

        val apiClient: ApiClient = new ApiClient()
        apiClient.setBasePath(dataRepoBaseUrl)
        apiClient.setAccessToken(ownerAuthToken.value)

        val dataRepoApi = new RepositoryApi(apiClient)

        val numSnapshots = 1

        // list and choose an available snapshot in TDR
        logger.info(s"calling data repo at $dataRepoBaseUrl as user ${owner.email} ... ")
        val drSnapshots = Try(dataRepoApi.enumerateSnapshots(
          0, numSnapshots, "created_date", "desc", "", "", java.util.Collections.emptyList() )) match {
          case Success(s) => s
          case Failure(ex) =>
            logger.error(s"data repo call as user ${owner.email} failed: ${ex.getMessage}", ex)
            throw ex
        }
        assume(drSnapshots.getItems.size() == numSnapshots,
          s"---> TDR at $dataRepoBaseUrl did not have $numSnapshots snapshots for this test to use!" +
            s" This is likely a problem in environment setup, but has a chance of being a problem in runtime code. <---")

        logger.info(s"found ${drSnapshots.getItems.size()} snapshot(s) from $dataRepoBaseUrl as user ${owner.email}: " +
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

        // on export completion, get job result
        val jobResult = dataRepoApi.retrieveJobResult(exportJob.getId)
        val snapshotExportModel:SnapshotExportResponseModel = jobResult match {
          case snapshotExport:SnapshotExportResponseModel => snapshotExport
          case x => fail(s"Data Repo snapshot export job result returned unexpected class ${x.getClass.getName}")
        }
        // parse job result, find manifest file
        val snapshotManifest = snapshotExportModel.getFormat.getParquet.getManifest

        snapshotManifest shouldBe "intentional failure, but we got this far"

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-snapshot-import-by-copy")) { workspaceName =>
            val entityTypeMetadataUrl = s"${workspaceUrl(projectName, workspaceName)}/entities"

            // verify that entity metadata for this workspace is empty
            val metadataResponse = Orchestration.getRequest(entityTypeMetadataUrl)
            val metadataBefore = Unmarshal(metadataResponse).to[Map[String, EntityTypeMetadata]].futureValue
            withClue("before snapshot import-by-copy, no data tables should exist") {
              metadataBefore shouldBe empty
            }

            // start async import of manifest file into a new workspace, response will be a jobid
            val startImportUrl = s"${workspaceUrl(projectName, workspaceName)}/importJob"
            val importPayload = s"""{ "filetype" : "tdrexport", "url" : "$snapshotManifest" }"""
            val importJobResponse = Orchestration.postRequest(startImportUrl, importPayload)

            // expect to get exactly one jobId back
            // TODO: centralize this jobId-extraction code into a reusable method
            val importJobIdValues: Seq[JsValue] = importJobResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1

            val importJobId: String = importJobIdValues.head match {
              case js:JsString => js.value
              case x => fail("got an invalid jobId: " + x.toString())
            }

            // poll for completion as owner
            eventually {
              val resp: HttpResponse = Orchestration.getRequest( s"${workspaceUrl(projectName, workspaceName)}/importPFB/$importJobId")
              resp.status shouldBe StatusCodes.OK
              blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value shouldBe JsString("Done")
            }

            // retrieve entity metadata for workspace, should have same types as snapshot tables
            val metadataAfter = Unmarshal(metadataResponse).to[Map[String, EntityTypeMetadata]].futureValue
            withClue("after snapshot import-by-copy, should have the same data tables as TDR") {
              metadataAfter.keySet should contain theSameElementsAs snapshotTableNames
            }
          }
        }



      }
    }
  }

  private def importAsync(projectName: String, workspaceName: String, importFilePath: String, expectedResult: Map[String, EntityTypeMetadata])(implicit authToken: AuthToken): Unit = {
    val startTime = System.currentTimeMillis()
    val importFileString = FileUtils.readAllTextFromResource(importFilePath)

    // call import as owner
    val postResponse: String = Orchestration.importMetaDataFlexible(projectName, workspaceName, isAsync = true, "entities", importFileString)
    // expect to get exactly one jobId back
    val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
    importJobIdValues should have size 1

    val importJobId: String = importJobIdValues.head match {
      case js: JsString => js.value
      case x => fail("got an invalid jobId: " + x.toString ())
    }

    logger.info(s"$projectName/$workspaceName has import job $importJobId for file $importFilePath")

    // poll for completion as owner
    withClue(s"import job $importJobId failed its eventually assertions on job status: ") {
      eventually(timeout = Timeout(scaled(Span(10, Minutes))), interval = Interval(scaled(Span(5, Seconds)))) {
        val requestId = scala.util.Random.alphanumeric.take(8).mkString // just to assist with logging
        logger.info(s"[$requestId] About to check status for import job $importJobId. Elapsed: ${humanReadableMillis(System.currentTimeMillis()-startTime)}")
        val resp: HttpResponse = Orchestration.getRequest(s"${workspaceUrl(projectName, workspaceName)}/importJob/$importJobId")
        val respStatus = resp.status
        logger.info(s"[$requestId] HTTP response status for import job $importJobId status request is [${respStatus.intValue()}]. Elapsed: ${humanReadableMillis(System.currentTimeMillis()-startTime)}")
        respStatus shouldBe StatusCodes.OK
        val importStatus = blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value
        logger.info(s"[$requestId] Import Service job status for import job $importJobId is [$importStatus]")
        importStatus shouldBe JsString ("Done")
      }
    }

    logger.info(s"$projectName/$workspaceName import job $importJobId completed for file $importFilePath in " +
      s"${humanReadableMillis(System.currentTimeMillis()-startTime)}. Now checking metadata ... ")

    // inspect data entities and confirm correct import as owner. With the import complete, the metadata should already
    // be correct, so we don't need to use eventually here.
    withClue(s"import job $importJobId failed its eventually assertion on entity metadata: ") {
      val resp = Orchestration.getRequest(s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$workspaceName/entities")
      val respStatus = resp.status
      logger.info(s"HTTP response status for import job $importJobId metadata request is [${respStatus.intValue()}]. Elapsed: ${humanReadableMillis(System.currentTimeMillis()-startTime)}")
      respStatus shouldBe StatusCodes.OK
      val result = blockForStringBody(resp).parseJson.convertTo[Map[String, EntityTypeMetadata]]
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
