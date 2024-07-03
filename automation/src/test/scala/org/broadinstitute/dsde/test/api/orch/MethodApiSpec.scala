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
  }
}
