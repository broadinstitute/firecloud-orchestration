package org.broadinstitute.dsde.test.api.orch

import java.util.UUID
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.broadinstitute.dsde.workbench.service.OrchestrationModel._
import org.scalatest.{FreeSpec, Matchers}
import spray.json._
import DefaultJsonProtocol._
import org.scalatest.time.{Minutes, Seconds, Span}
import org.scalatest.concurrent.Eventually

class WorkspaceApiSpec extends FreeSpec with Matchers with Eventually
  with BillingFixtures with WorkspaceFixtures {

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()

  "Orchestration" - {

    "should return a storage cost estimate" - {
      "for the owner of a workspace" in {
        implicit val token: AuthToken = ownerAuthToken

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("owner-storage-cost")) { workspaceName =>
            Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)

            val storageCostEstimate = Orchestration.workspaces.getStorageCostEstimate(projectName, workspaceName).parseJson.convertTo[StorageCostEstimate]
            storageCostEstimate.estimate should be ("$0.00")
          }
        }
      }

      "for writers of a workspace" in {
        val writer = UserPool.chooseStudent

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("writer-storage-cost"), aclEntries = List(AclEntry(writer.email, WorkspaceAccessLevel.Writer))) { workspaceName =>
            implicit val writerAuthToken: AuthToken = writer.makeAuthToken
            Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)
            Orchestration.workspaces.getStorageCostEstimate(projectName, workspaceName)
              .parseJson.convertTo[StorageCostEstimate]
              .estimate should be("$0.00")
          } (ownerAuthToken)
        }
      }
    }

    "should not return a storage cost estimate" - {
      "for readers of a workspace" in {
        val reader = UserPool.chooseStudent

        withCleanBillingProject(owner) { projectName =>
          withWorkspace(projectName, prependUUID("reader-storage-cost"), aclEntries = List(AclEntry(reader.email, WorkspaceAccessLevel.Reader))) { workspaceName =>
            implicit val readerAuthToken: AuthToken = reader.makeAuthToken
            Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)

            val exception = intercept[RestException] {
              Orchestration.workspaces.getStorageCostEstimate(projectName, workspaceName)
            }
            val exceptionMessage = exception.message.parseJson.asJsObject.fields("message").convertTo[String]

            exceptionMessage should include(s"insufficient permissions to perform operation on $projectName/$workspaceName")
          } (ownerAuthToken)
        }
      }
    }
  }

  private def prependUUID(suffix: String): String = s"${UUID.randomUUID().toString}-$suffix"
}
