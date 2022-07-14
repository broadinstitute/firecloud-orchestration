package org.broadinstitute.dsde.test.api.orch

import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.auth.AuthTokenScopes.billingScopes
import org.broadinstitute.dsde.workbench.config.{ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures.withTemporaryBillingProject
import org.broadinstitute.dsde.workbench.fixture._
import org.broadinstitute.dsde.workbench.service.test.RandomUtil
import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol._
import spray.json._

class MethodApiSpec
  extends AnyFreeSpec
    with Matchers
    with RandomUtil
    with WorkspaceFixtures
    with MethodFixtures {

  val billingAccountId: String = ServiceTestConfig.Projects.billingAccountId

  "For a method config that references a redacted method" - {
    "should be able to choose new method snapshot" in {
      val user = UserPool.chooseProjectOwner
      implicit val authToken: AuthToken = user.makeAuthToken()
      withTemporaryBillingProject(billingAccountId) { billingProject =>
        withWorkspace(billingProject, "MethodConfigApiSpec_choose_new_snapshot") { workspaceName =>
          withMethod("MethodRedactedSpec_choose_new_snapshot", MethodData.SimpleMethod, 2) { methodName =>
            val method = MethodData.SimpleMethod.copy(methodName = methodName)

            Orchestration.methodConfigurations.createMethodConfigInWorkspace(
              billingProject, workspaceName, method, SimpleMethodConfig.configNamespace, SimpleMethodConfig.configName, 1,
              SimpleMethodConfig.inputs, SimpleMethodConfig.outputs, SimpleMethodConfig.rootEntityType)

            Orchestration.methods.redact(method)

            val getMethodResponse = intercept[RestException]{ Orchestration.methods.getMethod(method.methodNamespace, method.methodName, method.snapshotId)}
            getMethodResponse.message should include (s"Methods Repository entity ${method.methodNamespace}/${method.methodName}/1 not found.")

            val newMethodSnapshotId = 2
            val methodRepoMethod = Map(
                  "methodName" -> method.methodName,
                  "methodVersion" -> newMethodSnapshotId,
                  "methodNamespace" -> method.methodNamespace,
                  "methodUri" -> s"agora://${method.methodNamespace}/${method.methodName}/$newMethodSnapshotId",
                  "sourceRepo" -> "agora"
            )

            val editedMethodConfig = Map(
              "deleted" -> false,
              "inputs"  -> SimpleMethodConfig.inputs,
              "outputs" -> SimpleMethodConfig.outputs,
              "methodConfigVersion" -> 1,
              "methodRepoMethod" -> methodRepoMethod,
              "name" -> SimpleMethodConfig.configName,
              "namespace" -> SimpleMethodConfig.configNamespace,
              "prerequisites" -> Map.empty,
              "rootEntityType" -> SimpleMethodConfig.rootEntityType
            )

            //choose new snapshot
            val editResponse = Orchestration.methodConfigurations.editMethodConfig(billingProject, workspaceName, SimpleMethodConfig.configNamespace, SimpleMethodConfig.configName, editedMethodConfig)
            editResponse should include (""""methodVersion":2""")
          }
        }
      }(user.makeAuthToken(billingScopes))
    }

    "launching an analysis should not be possible" in {
      val user = UserPool.chooseProjectOwner
      implicit val authToken: AuthToken = user.makeAuthToken()
      withTemporaryBillingProject(billingAccountId) { billingProject =>
        withWorkspace(billingProject, "MethodApiSpec_launch_after_redact") { workspaceName =>
          withMethod("MethodApiSpec_launch_after_redact", MethodData.SimpleMethod, cleanUp = false) { methodName =>
            val method = MethodData.SimpleMethod.copy(methodName = methodName)

            Orchestration.methodConfigurations.createMethodConfigInWorkspace(
              billingProject,
              workspaceName,
              method,
              SimpleMethodConfig.configNamespace,
              SimpleMethodConfig.configName,
              1,
              SimpleMethodConfig.inputs,
              SimpleMethodConfig.outputs,
              SimpleMethodConfig.rootEntityType)

            Orchestration.methods.redact(method)

            val participantId = randomIdWithPrefix(SimpleMethodConfig.rootEntityType)
            Orchestration.importMetaData(billingProject, workspaceName, "entities", s"entity:participant_id\n$participantId")

            val launchException = intercept[RestException]{
              Orchestration.submissions.launchWorkflow(
                ns = billingProject,
                wsName = workspaceName,
                methodConfigurationNamespace = SimpleMethodConfig.configNamespace,
                methodConfigurationName = SimpleMethodConfig.configName,
                entityType = SimpleMethodConfig.rootEntityType,
                entityName = participantId,
                expression = "this",
                useCallCache = false,
                deleteIntermediateOutputFiles = false,
                useReferenceDisks = false,
                memoryRetryMultiplier = 1
              )
            }

            launchException.message.parseJson.asJsObject.fields("statusCode").convertTo[Int] should be (404)
          }
        }
      }(user.makeAuthToken(billingScopes))
    }

    "the method config should be deleteable " in {
      val user = UserPool.chooseProjectOwner
      implicit val authToken: AuthToken = user.makeAuthToken()
      withTemporaryBillingProject(billingAccountId) { billingProject =>
        withWorkspace(billingProject, "MethodApiSpec_delete_after_redacted") { workspaceName =>
          withMethod("MethodApiSpec_delete_after_redacted", MethodData.SimpleMethod, cleanUp = false) { methodName =>
            val method = MethodData.SimpleMethod.copy(methodName = methodName)

            Orchestration.methodConfigurations.createMethodConfigInWorkspace(
              billingProject,
              workspaceName,
              method,
              SimpleMethodConfig.configNamespace,
              SimpleMethodConfig.configName,
              1,
              SimpleMethodConfig.inputs,
              SimpleMethodConfig.outputs,
              SimpleMethodConfig.rootEntityType)

            Orchestration.methods.redact(method)

            Orchestration.methodConfigurations.deleteMethodConfig(billingProject, workspaceName, SimpleMethodConfig.configNamespace, SimpleMethodConfig.configName)
          }
        }
      }(user.makeAuthToken(billingScopes))
    }
  }
}
