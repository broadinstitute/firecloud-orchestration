package org.broadinstitute.dsde.test.api.orch

import java.util.UUID

import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.scalatest.{FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

class PFBImportSpec extends FreeSpec with Matchers
  with BillingFixtures with WorkspaceFixtures {

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()

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
              Orchestration.postRequest(importURL(projectName, workspaceName), "{}")(reader.makeAuthToken())
            }

            val exceptionObject = exception.message.parseJson.asJsObject

            exceptionObject.fields("message").convertTo[String] should contain "insufficient permissions to perform operation on $projectName/$workspaceName"
            exceptionObject.fields("status").convertTo[Int] shouldBe 403

          } (ownerAuthToken)
        }
      }
    }
  }

  private def prependUUID(suffix: String): String = s"${UUID.randomUUID().toString}-$suffix"

  private def importURL(projectName: String, wsName: String): String =
    s"${ServiceTestConfig.FireCloud.orchApiUrl}api/workspaces/$projectName/$wsName/importPFB"

}
