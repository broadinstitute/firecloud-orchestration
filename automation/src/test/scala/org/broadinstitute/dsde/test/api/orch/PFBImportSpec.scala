package org.broadinstitute.dsde.test.api.orch

import java.util.UUID
import java.util.concurrent.ForkJoinPool

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.scalatest.{FreeSpec, Matchers}
import org.scalatest.OptionValues._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.Await
import scala.concurrent.duration._
//import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext
import scala.io.Source

class PFBImportSpec extends FreeSpec with Matchers with Eventually with ScalaFutures
  with BillingFixtures with WorkspaceFixtures with Orchestration {

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()

//  final implicit val system: ActorSystem = ActorSystem("PFBImportSpec")
//  final implicit val materializer: Materializer = ActorMaterializer(ActorMaterializerSettings(system))
//  final implicit val context: ExecutionContext = system.dispatcher // for the eventually{} and async testing framework
//  private val customExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(new ForkJoinPool()) // for the futures being tested

  final implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(60, Seconds)), interval = scaled(Span(2, Seconds)))

  // this test.avro is copied from PyPFB's fixture at https://github.com/uc-cdis/pypfb/tree/master/tests/pfb-data
  private val testPayload = Map("url" -> "https://storage.googleapis.com/fixtures-for-tests/fixtures/public/test.avro")
  lazy private val expectedEntities: JsValue = Source.fromResource("PFBImportSpec-expected-entities.json").getLines().mkString.parseJson

  "Orchestration" - {

    "should import a PFB file via import service" - {
      "for the owner of a workspace" in {
        implicit val token: AuthToken = ownerAuthToken

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            // Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)

            // call importPFB as owner
            val postResponse: String = Orchestration.postRequest(importURL(projectName, workspaceName), testPayload)
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1

            logger.warn(s">>>>>>>>>>>>>>>>>>>>>> passed importJobId size check")
            val importJobId: String = importJobIdValues.head match {
              case js:JsString => js.value
              case x => fail("got in invalid jobId: " + x.toString())
            }
            logger.warn(s">>>>>>>>>>>>>>>>>>>>>> using importJobId $importJobId")
            logger.warn(s">>>>>>>>>>>>>>>>>>>>>> using job-status url ${importURL(projectName, workspaceName)}/$importJobId")

            // poll for completion as owner
//            eventually {
              val resp: HttpResponse = Orchestration.getRequest( s"${importURL(projectName, workspaceName)}/$importJobId")
              resp.status shouldBe StatusCodes.OK
              // blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value shouldBe "Done"
//            }

            // inspect data entities and confirm correct import as owner
//            eventually {
//              val resp = Orchestration.getRequest( s"${importURL(projectName, workspaceName)}/entities")
//              blockForStringBody(resp).parseJson shouldBe expectedEntities
//            }

          }
        }
      }

      "for writers of a workspace" ignore {
        val writer = UserPool.chooseStudent
        val writerToken = writer.makeAuthToken()

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("writer-pfb-import"), aclEntries = List(AclEntry(writer.email, WorkspaceAccessLevel.Writer))) { workspaceName =>
            // Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)(ownerAuthToken)

            // call importPFB as writer
            val postResponse: String = Orchestration.postRequest(importURL(projectName, workspaceName), testPayload)(writerToken)
            // expect to get exactly one jobId back
            val importJobIdValues: Seq[JsValue] = postResponse.parseJson.asJsObject.getFields("jobId")
            importJobIdValues should have size 1
            val importJobId: String = importJobIdValues.head.toString

            // poll for completion as writer
            eventually {
              val resp = Orchestration.getRequest( s"${importURL(projectName, workspaceName)}/$importJobId")(writerToken)
              blockForStringBody(resp).parseJson.asJsObject.fields.get("status").value shouldBe "Done"
            }

            // inspect data entities and confirm correct import as writer
            eventually {
              val resp = Orchestration.getRequest( s"${importURL(projectName, workspaceName)}/entities")(writerToken)
              blockForStringBody(resp).parseJson shouldBe expectedEntities
            }

          } (ownerAuthToken)
        }
      }
    }

    "should return an error when attempting to import a PFB file via import service" - {

      "for readers of a workspace" in {
        val reader = UserPool.chooseStudent

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("reader-pfb-import"), aclEntries = List(AclEntry(reader.email, WorkspaceAccessLevel.Reader))) { workspaceName =>
            // Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)(ownerAuthToken)

            // call importPFB as reader
            val exception = intercept[RestException] {
              Orchestration.postRequest(importURL(projectName, workspaceName), testPayload)(reader.makeAuthToken())
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
            // Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)(ownerAuthToken)

            // call importPFB with a payload of the wrong shape
            val exception = intercept[RestException] {
              Orchestration.postRequest(importURL(projectName, workspaceName), "this is a string, not json")
            }

            val errorReport = exception.message.parseJson.convertTo[ErrorReport]

            errorReport.statusCode.value shouldBe StatusCodes.BadRequest
            errorReport.message should include (s"Object expected in field 'url'")

          } (ownerAuthToken)
        }
      }
      // N.B. other failures, such as an invalid PFB or PFB-not-found, fail asynchronously. Import Service accepts the
      // job, starts to process it, and then will mark the job as failed. Is that worth testing here?

    }
  }

  private def prependUUID(suffix: String): String = s"${UUID.randomUUID().toString}-$suffix"

  private def blockForStringBody(response: HttpResponse): String = {
//    extractResponseString(response)
    Unmarshal(response.entity).to[String].futureValue
//    import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.stringUnmarshaller
//    implicit val executionContext: ExecutionContext = customExecutionContext
//    Await.result(Unmarshal(response.entity).to[String](um = stringUnmarshaller, ec = executionContext, mat = materializer), 5.seconds)
  }

  private def importURL(projectName: String, wsName: String): String =
    s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$wsName/importPFB"

}
