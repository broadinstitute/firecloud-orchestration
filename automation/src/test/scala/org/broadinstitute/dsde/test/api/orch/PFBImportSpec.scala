package org.broadinstitute.dsde.test.api.orch

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport.ErrorReportFormat
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.scalatest.{FreeSpec, Matchers}
import org.scalatest.OptionValues._
import spray.json.DefaultJsonProtocol._
import spray.json._


class PFBImportSpec extends FreeSpec with Matchers
  with BillingFixtures with WorkspaceFixtures {

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()

  // maybe steal the test.avro from https://github.com/uc-cdis/pypfb/tree/master/tests/pfb-data ?
  // or do a small export from BDC staging env ?
  val testPayload = Map("url" -> "gs://fixtures-for-tests/example.pfb")

  "Orchestration" - {

    "should import a PFB file via import service" - {
      "for the owner of a workspace" ignore {
        implicit val token: AuthToken = ownerAuthToken

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-pfb-import")) { workspaceName =>
            // Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)

            // TODO: call importPFB as owner
            // TODO: poll for completion as owner
            // TODO: inspect data entities and confirm correct import as owner
          }
        }
      }

      "for writers of a workspace" ignore {
        val writer = UserPool.chooseStudent

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("writer-pfb-import"), aclEntries = List(AclEntry(writer.email, WorkspaceAccessLevel.Writer))) { workspaceName =>
            // Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)(ownerAuthToken)

            // TODO: call importPFB as writer
            // TODO: poll for completion as writer
            // TODO: inspect data entities and confirm correct import as writer

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
    }
  }

  private def prependUUID(suffix: String): String = s"${UUID.randomUUID().toString}-$suffix"

  private def importURL(projectName: String, wsName: String): String =
    s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$wsName/importPFB"

}