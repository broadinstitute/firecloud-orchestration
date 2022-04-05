package org.broadinstitute.dsde.test.api.orch

import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.auth.AuthTokenScopes.billingScopes
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures.withTemporaryBillingProject
import org.broadinstitute.dsde.workbench.fixture.WorkspaceFixtures
import org.broadinstitute.dsde.workbench.service.OrchestrationModel._
import org.broadinstitute.dsde.workbench.service.{AclEntry, Orchestration, RestException, WorkspaceAccessLevel}
import org.scalatest.concurrent.Eventually
import org.scalatest.{FreeSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

import java.util.UUID

class WorkspaceApiSpec
  extends FreeSpec
    with Matchers
    with Eventually
    with WorkspaceFixtures {

  val owner: Credentials = UserPool.chooseProjectOwner
  val ownerAuthToken: AuthToken = owner.makeAuthToken()
  val billingAccountName: String = ServiceTestConfig.Projects.billingAccountId

  "Orchestration" - {

    "should return a storage cost estimate" - {
      "for the owner of a workspace" in {
        implicit val token: AuthToken = ownerAuthToken

        withTemporaryBillingProject(billingAccountName) { projectName =>
          withWorkspace(projectName, prependUUID("owner-storage-cost")) { workspaceName =>
            Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)

            val storageCostEstimate = Orchestration.workspaces.getStorageCostEstimate(projectName, workspaceName).parseJson.convertTo[StorageCostEstimate]
            storageCostEstimate.estimate should be ("$0.00")
          }
        }(owner.makeAuthToken(billingScopes))
      }

      "for writers of a workspace" in {
        val writer = UserPool.chooseStudent

        withTemporaryBillingProject(billingAccountName) { projectName =>
          withWorkspace(projectName, prependUUID("writer-storage-cost"), aclEntries = List(AclEntry(writer.email, WorkspaceAccessLevel.Writer))) { workspaceName =>
            implicit val writerAuthToken: AuthToken = writer.makeAuthToken
            Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)
            Orchestration.workspaces.getStorageCostEstimate(projectName, workspaceName)
              .parseJson.convertTo[StorageCostEstimate]
              .estimate should be("$0.00")
          } (ownerAuthToken)
        }(owner.makeAuthToken(billingScopes))
      }
    }

    "should not return a storage cost estimate" - {
      "for readers of a workspace" in {
        val reader = UserPool.chooseStudent

        withTemporaryBillingProject(billingAccountName) { projectName =>
          withWorkspace(projectName, prependUUID("reader-storage-cost"), aclEntries = List(AclEntry(reader.email, WorkspaceAccessLevel.Reader))) { workspaceName =>
            implicit val readerAuthToken: AuthToken = reader.makeAuthToken
            Orchestration.workspaces.waitForBucketReadAccess(projectName, workspaceName)

            val exception = intercept[RestException] {
              Orchestration.workspaces.getStorageCostEstimate(projectName, workspaceName)
            }
            val exceptionMessage = exception.message.parseJson.asJsObject.fields("message").convertTo[String]

            exceptionMessage should include(s"insufficient permissions to perform operation on $projectName/$workspaceName")
          } (ownerAuthToken)
        }(owner.makeAuthToken(billingScopes))
      }
    }
  }

  private def prependUUID(suffix: String): String = s"${UUID.randomUUID().toString}-$suffix"
}
