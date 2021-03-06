package org.broadinstitute.dsde.test.api.orch

import java.util.UUID

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import org.broadinstitute.dsde.rawls.model.EntityTypeMetadata
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.EntityTypeMetadataFormat
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.scalatest.OptionValues._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import spray.json._

import scala.io.Source

class PFBImportSpec extends FreeSpec with Matchers with Eventually with ScalaFutures
  with BillingFixtures with WorkspaceFixtures with Orchestration {

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()

  final implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(300, Seconds)), interval = scaled(Span(2, Seconds)))

  // this test.avro is copied from PyPFB's fixture at https://github.com/uc-cdis/pypfb/tree/master/tests/pfb-data
  private val testPayload = Map("url" -> "https://storage.googleapis.com/fixtures-for-tests/fixtures/public/test.avro")
  lazy private val expectedEntities: Map[String, EntityTypeMetadata] = Source.fromResource("PFBImportSpec-expected-entities.json").getLines().mkString
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
              case x => fail("got in invalid jobId: " + x.toString())
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
              case x => fail("got in invalid jobId: " + x.toString())
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
              case x => fail("got in invalid jobId: " + x.toString())
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
  }

  private def prependUUID(suffix: String): String = s"${UUID.randomUUID().toString}-$suffix"

  private def blockForStringBody(response: HttpResponse): String =
    Unmarshal(response.entity).to[String].futureValue

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
