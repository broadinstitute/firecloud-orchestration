package org.broadinstitute.dsde.test.api.orch

import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.UserPool
import org.broadinstitute.dsde.workbench.fixture._
import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException}
import org.scalatest.{FreeSpec, Matchers}
import spray.json._
import DefaultJsonProtocol._

class MethodApiSpec extends FreeSpec with Matchers
  with BillingFixtures with WorkspaceFixtures with MethodFixtures {

  "For a method config that references a redacted method" - {
    "should be able to choose new method snapshot" in {
      val user = UserPool.chooseProjectOwner
      implicit val authToken: AuthToken = user.makeAuthToken()
      withCleanBillingProject(user) { billingProject =>
        withWorkspace(billingProject, "MethodConfigApiSpec_choose_new_snapshot") { workspaceName =>
          withMethod("MethodRedactedSpec_choose_new_snapshot", MethodData.SimpleMethod, 2) { methodName =>
            val method = MethodData.SimpleMethod.copy(methodName = methodName)

            Orchestration.methodConfigurations.createMethodConfigInWorkspace(
              billingProject, workspaceName, method, SimpleMethodConfig.configNamespace, SimpleMethodConfig.configName, 1,
              SimpleMethodConfig.inputs, SimpleMethodConfig.outputs, SimpleMethodConfig.rootEntityType)

            Orchestration.methods.redact(method)

            //Terra uses orch for methods and rawls for method configs
            //Rawls.methodConfigs.getMethodConfigInWorkspace()

            //get method
            val getMethodResponse = intercept[RestException]{ Orchestration.methods.getMethod(method.methodNamespace, method.methodName, method.snapshotId)}
            println("getMethodResponse " + getMethodResponse.toString)
            getMethodResponse.message should include("")

            //            {
            //  "deleted": false,
            //  "inputs": {
            //    "optional.optionalInput": "",
            //    "optional.t.b": "",
            //    "optional.t.i": ""
            //  },
            //  "methodConfigVersion": 1,
            //  "methodRepoMethod": {
            //    "methodName": "optional_multiple",
            //    "methodVersion": 1,
            //    "methodNamespace": "anutest",
            //    "methodUri": "agora://anutest/optional_multiple/1",
            //    "sourceRepo": "agora"
            //  },
            //  "name": "a_optional_multiple",
            //  "namespace": "anutest",
            //  "outputs": {
            //    "optional.didItWork": ""
            //  },
            //  "prerequisites": {},
            //  "rootEntityType": "participant"
            //}

            val newMethodSnapshotId = 2
            val methodRepoMethod = Map(
                  "methodName" -> method.methodName,
                  "methodVersion" -> newMethodSnapshotId,
                  "methodNamespace" -> method.methodNamespace,
                  "methodUri" -> s"agora://${method.methodNamespace}/${method.methodName}/$newMethodSnapshotId",
                  "sourceRepo" -> "agora"
            )

            val editedMethodConfig = Map(
              "deleted" -> "false",
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
            val editResponse = Orchestration.methodConfigurations.editMethodConfig(billingProject, workspaceName, method.methodNamespace, method.methodName, editedMethodConfig)
            println("editResponse " + editResponse)
            editResponse should include (""""methodVersion": 2,""")

          }
        }
      }
    }

//    "launch analysis and delete errors on redacted method" in {
//      val user = UserPool.chooseProjectOwner
//      implicit val authToken: AuthToken = user.makeAuthToken()
//      withCleanBillingProject(user) { billingProject =>
//        withWorkspace(billingProject, "MethodRedactedSpec_delete") { workspaceName =>
//          withMethod("MethodRedactedSpec_delete", MethodData.SimpleMethod, cleanUp = false) { methodName =>
//            val method = MethodData.SimpleMethod.copy(methodName = methodName)
//            api.methodConfigurations.createMethodConfigInWorkspace(billingProject, workspaceName, method,
//              SimpleMethodConfig.configNamespace, methodConfigName, 1, SimpleMethodConfig.inputs, SimpleMethodConfig.outputs, "participant")
//            api.methods.redact(method)
//
//            withWebDriver { implicit driver =>
//              withSignIn(user) { _ =>
//                // launch analysis error:
//                // should have warning icon in config list
//                val workspaceMethodConfigPage = new WorkspaceMethodConfigListPage(billingProject, workspaceName).open
//                workspaceMethodConfigPage.hasRedactedIcon(methodConfigName) shouldBe true
//
//                // should be disabled and show error if clicked
//                val methodConfigDetailsPage = new WorkspaceMethodConfigDetailsPage(billingProject, workspaceName, SimpleMethodConfig.configNamespace, methodConfigName).open
//                val errorModal = methodConfigDetailsPage.clickLaunchAnalysisButtonError()
//                errorModal.isVisible shouldBe true
//                errorModal.clickOk() // dismiss modal
//
//                // should be able to be deleted when no unredacted snapshot exists
//                methodConfigDetailsPage.openEditMode(expectSuccess = false)
//                val modal = await ready new MessageModal()
//                modal.isVisible shouldBe true
//                modal.getMessageText should include("There are no available method snapshots")
//                modal.clickOk()
//
//                methodConfigDetailsPage.deleteMethodConfig()
//                val list = await ready new WorkspaceMethodConfigListPage(billingProject, workspaceName)
//                list.hasConfig(methodConfigName) shouldBe false
//              }
//            }
//          }
//        }
//      }
//    }
  }


}
