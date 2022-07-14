package org.broadinstitute.dsde.test.api.orch

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import org.broadinstitute.dsde.rawls.model.EntityTypeMetadata
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.EntityTypeMetadataFormat
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.auth.AuthTokenScopes.billingScopes
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures.withTemporaryBillingProject
import org.broadinstitute.dsde.workbench.fixture.WorkspaceFixtures
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.parboiled.common.FileUtils
import org.scalatest.OptionValues._
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minutes, Seconds, Span}
import spray.json._

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.io.Source
import scala.util.{Failure, Success, Try}


class AsyncImportSpec
  extends AnyFreeSpec
    with Matchers
    with AsyncTestUtils
    with ScalaFutures
    with WorkspaceFixtures
    with Orchestration {

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()
  val billingAccountId: String = ServiceTestConfig.Projects.billingAccountId

  final implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(300, Seconds)), interval = scaled(Span(2, Seconds)))

  // this test.avro is copied from PyPFB's fixture at https://github.com/uc-cdis/pypfb/tree/master/tests/pfb-data
  private val testPayload = Map("url" -> "https://storage.googleapis.com/fixtures-for-tests/fixtures/public/test.avro")

  lazy private val expectedEntities: Map[String, EntityTypeMetadata] = Source.fromResource("AsyncImportSpec-pfb-expected-entities.json").getLines().mkString
    .parseJson.convertTo[Map[String, EntityTypeMetadata]]

  "Orchestration" - {

    "should import a PFB file via import service" - {
      "for the owner of a workspace" in {
        implicit val token: AuthToken = ownerAuthToken

        withTemporaryBillingProject(billingAccountId) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            // call importPFB as owner
            val postResponse: String = Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", testPayload)
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1

            val importJobId: String = importJobIdValues.head match {
              case js: JsString => js.value
              case x => fail("got an invalid jobId: " + x.toString())
            }

            // poll for completion as owner
            eventuallyWithEscape {
              val resp: HttpResponse = Orchestration.getRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB/$importJobId")
              resp.status shouldBe StatusCodes.OK
              val importStatus = blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value
              assertWithEscape(
                importStatus shouldBe JsString("Done"),
                importStatus should not equal JsString("Error"),
                "Error encountered during import, failing test early"
              )
            }

            // inspect data entities and confirm correct import as owner
            eventually {
              val resp = Orchestration.getRequest(s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$workspaceName/entities")
              compareMetadata(blockForStringBody(resp).parseJson.convertTo[Map[String, EntityTypeMetadata]], expectedEntities)
            }

          }
        }(owner.makeAuthToken(billingScopes))
      }

      "for writers of a workspace" in {
        val writer = UserPool.chooseStudent
        val writerToken = writer.makeAuthToken()

        withTemporaryBillingProject(billingAccountId) { projectName =>
          withWorkspace(projectName, prependUUID("writer-pfb-import"), aclEntries = List(AclEntry(writer.email, WorkspaceAccessLevel.Writer))) { workspaceName =>
            // call importPFB as writer
            val postResponse: String = Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", testPayload)(writerToken)
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1
            val importJobId: String = importJobIdValues.head match {
              case js: JsString => js.value
              case x => fail("got an invalid jobId: " + x.toString())
            }

            // poll for completion as writer
            eventuallyWithEscape {
              val resp = Orchestration.getRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB/$importJobId")(writerToken)
              val importStatus = blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value
              assertWithEscape(
                importStatus shouldBe JsString("Done"),
                importStatus should not equal JsString("Error"),
                "Error encountered during import, failing test early"
              )
            }

            // inspect data entities and confirm correct import as writer
            eventually {
              val resp = Orchestration.getRequest(s"${workspaceUrl(projectName, workspaceName)}/entities")(writerToken)
              compareMetadata(blockForStringBody(resp).parseJson.convertTo[Map[String, EntityTypeMetadata]], expectedEntities)
            }

          }(ownerAuthToken)
        }(owner.makeAuthToken(billingScopes))
      }
    }

    "should asynchronously result in an error when attempting to import a PFB file via import service" - {
      "if the file to be imported is invalid" in {
        implicit val token: AuthToken = ownerAuthToken

        withTemporaryBillingProject(billingAccountId) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            // call importPFB as owner
            val postResponse: String = Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB",
              Map("url" -> "https://storage.googleapis.com/fixtures-for-tests/fixtures/this-intentionally-does-not-exist"))
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1

            val importJobId: String = importJobIdValues.head match {
              case js: JsString => js.value
              case x => fail("got an invalid jobId: " + x.toString())
            }

            // poll for completion as owner
            eventually {
              val resp: HttpResponse = Orchestration.getRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB/$importJobId")
              resp.status shouldBe StatusCodes.OK
              blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value shouldBe JsString("Error")
            }
          }
        }(owner.makeAuthToken(billingScopes))
      }
    }

    "should synchronously return an error when attempting to import a PFB file via import service" - {

      "for readers of a workspace" in {
        val reader = UserPool.chooseStudent

        withTemporaryBillingProject(billingAccountId) { projectName =>
          withWorkspace(projectName, prependUUID("reader-pfb-import"), aclEntries = List(AclEntry(reader.email, WorkspaceAccessLevel.Reader))) { workspaceName =>

            // call importPFB as reader
            val exception = intercept[RestException] {
              Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", testPayload)(reader.makeAuthToken())
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.Forbidden
            errorReport.message should include(s"Cannot perform the action write on $projectName/$workspaceName")

          }(ownerAuthToken)
        }(owner.makeAuthToken(billingScopes))
      }

      "with an invalid POST payload" in {
        implicit val token: AuthToken = ownerAuthToken
        withTemporaryBillingProject(billingAccountId) { projectName =>
          withWorkspace(projectName, prependUUID("reader-pfb-import")) { workspaceName =>

            // call importPFB with a payload of the wrong shape
            val exception = intercept[RestException] {
              Orchestration.postRequest(s"${workspaceUrl(projectName, workspaceName)}/importPFB", "this is a string, not json")
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.BadRequest
            errorReport.message should include(s"Object expected in field 'url'")

          }(ownerAuthToken)
        }(owner.makeAuthToken(billingScopes))
      }

    }

    "should import a TSV asynchronously for entities and entity membership" in {
      implicit val token: AuthToken = ownerAuthToken
      withTemporaryBillingProject(billingAccountId) { projectName =>
        withWorkspace(projectName, prependUUID("tsv-entities")) { workspaceName =>
          val participantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq()))
          val participantAndSetEntityMetadata = participantEntityMetadata + ("participant_set" -> EntityTypeMetadata(2, "participant_set_id", Seq("participants")))

          importAsync(projectName, workspaceName, "ADD_PARTICIPANTS.tsv", participantEntityMetadata)
          importAsync(projectName, workspaceName, "MEMBERSHIP_PARTICIPANT_SET.tsv", participantAndSetEntityMetadata)
        }
      }(owner.makeAuthToken(billingScopes))
    }

    "should import a TSV asynchronously for updates" in {
      implicit val token: AuthToken = ownerAuthToken
      withTemporaryBillingProject(billingAccountId) { projectName =>
        withWorkspace(projectName, prependUUID("tsv-updates")) { workspaceName =>
          val participantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq()))
          val updatedParticipantEntityMetadata = Map("participant" -> EntityTypeMetadata(8, "participant_id", Seq("age")))

          importAsync(projectName, workspaceName, "ADD_PARTICIPANTS.tsv", participantEntityMetadata)
          importAsync(projectName, workspaceName, "UPDATE_PARTICIPANTS.tsv", updatedParticipantEntityMetadata)
        }
      }(owner.makeAuthToken(billingScopes))

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
      case x => fail("got an invalid jobId: " + x.toString())
    }

    logger.info(s"$projectName/$workspaceName has import job $importJobId for file $importFilePath")

    // poll for completion as owner
    withClue(s"import job $importJobId failed its eventually assertions on job status: ") {
      eventuallyWithEscape(Timeout(scaled(Span(10, Minutes))), Interval(scaled(Span(5, Seconds)))) {
        val requestId = scala.util.Random.alphanumeric.take(8).mkString // just to assist with logging
        logger.info(s"[$requestId] About to check status for import job $importJobId. Elapsed: ${humanReadableMillis(System.currentTimeMillis() - startTime)}")
        val resp: HttpResponse = Orchestration.getRequest(s"${workspaceUrl(projectName, workspaceName)}/importJob/$importJobId")
        val respStatus = resp.status
        logger.info(s"[$requestId] HTTP response status for import job $importJobId status request is [${respStatus.intValue()}]. Elapsed: ${humanReadableMillis(System.currentTimeMillis() - startTime)}")
        respStatus shouldBe StatusCodes.OK
        val importStatus = blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value
        logger.info(s"[$requestId] Import Service job status for import job $importJobId is [$importStatus]")
        assertWithEscape(importStatus shouldBe JsString("Done"), importStatus should not equal JsString("Error"), "Import error occurred, failing test")
      }
    }

    logger.info(s"$projectName/$workspaceName import job $importJobId completed for file $importFilePath in " +
      s"${humanReadableMillis(System.currentTimeMillis() - startTime)}. Now checking metadata ... ")

    // inspect data entities and confirm correct import as owner. With the import complete, the metadata should already
    // be correct, so we don't need to use eventually here.
    withClue(s"import job $importJobId failed its eventually assertion on entity metadata: ") {
      val resp = Orchestration.getRequest(s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$workspaceName/entities")
      val respStatus = resp.status
      logger.info(s"HTTP response status for import job $importJobId metadata request is [${respStatus.intValue()}]. Elapsed: ${humanReadableMillis(System.currentTimeMillis() - startTime)}")
      respStatus shouldBe StatusCodes.OK
      val result = blockForStringBody(resp).parseJson.convertTo[Map[String, EntityTypeMetadata]]
      compareMetadata(result, expectedResult)
    }

    logger.info(s"$projectName/$workspaceName import job $importJobId metadata check for file $importFilePath passed in " +
      s"${humanReadableMillis(System.currentTimeMillis() - startTime)}. importAsync() complete.")

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
