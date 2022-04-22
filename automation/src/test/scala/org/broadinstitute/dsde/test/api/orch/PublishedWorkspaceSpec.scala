package org.broadinstitute.dsde.test.api.orch

import cats.implicits.catsSyntaxOptionId
import org.broadinstitute.dsde.test.LibraryData
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.auth.AuthTokenScopes.billingScopes
import org.broadinstitute.dsde.workbench.config.{ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures.withTemporaryBillingProject
import org.broadinstitute.dsde.workbench.fixture.WorkspaceFixtures
import org.broadinstitute.dsde.workbench.service.test.RandomUtil
import org.broadinstitute.dsde.workbench.service.{Orchestration, Rawls}
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import spray.json._


class PublishedWorkspaceSpec
  extends AnyFreeSpec
    with WorkspaceFixtures
    with Matchers
    with Eventually
    with RandomUtil {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(30, Seconds)), interval = scaled(Span(2, Seconds)))
  val billingAccountId: String = ServiceTestConfig.Projects.billingAccountId

  "a user with publish permissions" - {
    "can publish a workspace" - {

      "published workspace should be visible in the library table" in {

        val curatorUser = UserPool.chooseCurator
        implicit val curatorAuthToken: AuthToken = curatorUser.makeAuthToken()

        withTemporaryBillingProject(billingAccountId, users = List(curatorUser.email).some) { billingProject =>
          withWorkspace(billingProject, "PublishedWorkspaceSpec_workspace") { workspaceName =>

            val data = LibraryData.metadataBasic + ("library:datasetName" -> workspaceName)
            Orchestration.library.setLibraryAttributes(billingProject, workspaceName, data)
            Orchestration.library.publishWorkspace(billingProject, workspaceName)

            withClue("a published workspace should be visible in library.") {
              eventually {
                isVisibleInLibrary(workspaceName) shouldBe true
              }
            }

            // should be able to be unpublished
            Orchestration.library.unpublishWorkspace(billingProject, workspaceName)

            // is not visible in the library table
            // to keep the test from failing (let Elasticsearch catch up)
            withClue("an unpublished workspace should not be visible in library.") {
              eventually {
                isVisibleInLibrary(workspaceName) shouldBe false
              }
            }
          }
        }(UserPool.chooseProjectOwner.makeAuthToken(billingScopes))
      }

      "can clone a published workspace" - {
        "cloned workspace should default to unpublished status" in {

          val curatorUser = UserPool.chooseCurator
          implicit val curatorAuthToken: AuthToken = curatorUser.makeAuthToken()

          withTemporaryBillingProject(billingAccountId, users = List(curatorUser.email).some) { billingProject =>
            withWorkspace(billingProject, "PublishedWorkspaceSpec_workspace") { workspaceName =>
              val data = LibraryData.metadataBasic + ("library:datasetName" -> workspaceName)
              Orchestration.library.setLibraryAttributes(billingProject, workspaceName, data)
              Orchestration.library.publishWorkspace(billingProject, workspaceName)

              val clonedWorkspaceName = uuidWithPrefix("cloneWorkspace")
              Orchestration.workspaces.clone(billingProject, workspaceName, billingProject, clonedWorkspaceName)
              try {
                // check cloned workspace does not have published status in workspace detail: "library:published": false
                val response = Rawls.workspaces.getWorkspaceDetails(billingProject, clonedWorkspaceName)
                response should include(""""library:published":false""")

                // check cloned workspace is not visible in library
                withClue("a cloned workspace should not be visible in library.") {
                  eventually {
                    isVisibleInLibrary(clonedWorkspaceName) shouldBe false
                  }
                }
              }
              finally Orchestration.workspaces.delete(billingProject, clonedWorkspaceName)
            }
          }(UserPool.chooseProjectOwner.makeAuthToken(billingScopes))
        }

        "cloned workspace should default to visible to 'all users'" in {

          val curatorUser = UserPool.chooseCurator
          implicit val curatorAuthToken: AuthToken = curatorUser.makeAuthToken()

          withTemporaryBillingProject(billingAccountId, users = List(curatorUser.email).some) { billingProject =>
            withWorkspace(billingProject, "PublishedWorkspaceSpec_workspace") { workspaceName =>
              val data = LibraryData.metadataBasic + ("library:datasetName" -> workspaceName)
              Orchestration.library.setLibraryAttributes(billingProject, workspaceName, data)
              Orchestration.library.setDiscoverableGroups(billingProject, workspaceName, List("all_broad_users"))
              Orchestration.library.publishWorkspace(billingProject, workspaceName)

              val clonedWorkspaceName = workspaceName + "_clone"
              Orchestration.workspaces.clone(billingProject, workspaceName, billingProject, clonedWorkspaceName)
              try {
                //Verify default group "All users"
                //In swagger you make sure that getDiscoverableGroup endpoint shows []
                withClue(s"Get api/library/${billingProject}/${clonedWorkspaceName}/discoverableGroups endpoint shows []") {
                  eventually {
                    val accessGroup: Seq[String] = Orchestration.library.getDiscoverableGroups(billingProject, clonedWorkspaceName)
                    accessGroup.size shouldBe 0
                  }
                }
              }
              finally Orchestration.workspaces.delete(billingProject, clonedWorkspaceName)
            }
          }(UserPool.chooseProjectOwner.makeAuthToken(billingScopes))
        }

      }

      "publish a dataset with consent codes" in {

        val curatorUser = UserPool.chooseCurator
        implicit val authToken: AuthToken = curatorUser.makeAuthToken()

        withTemporaryBillingProject(billingAccountId, users = List(curatorUser.email).some) { billingProject =>
          withWorkspace(billingProject, "PublishedWorkspaceSpec_consentcodes") { workspaceName =>

            val data = LibraryData.metadataBasic + ("library:datasetName" -> workspaceName) ++ LibraryData.consentCodes
            Orchestration.library.setLibraryAttributes(billingProject, workspaceName, data)
            Orchestration.library.publishWorkspace(billingProject, workspaceName)

            withClue("find library:consentCodes in library dataset") {
              eventually {
                val codes = getDatasetFieldValues(workspaceName, "library:consentCodes")
                codes should contain theSameElementsAs List("NPU", "NCU", "HMB", "NMDS")
              }
            }
          }
        }(UserPool.chooseProjectOwner.makeAuthToken(billingScopes))
      }

      "publish a dataset with tags" in {

        val tags = Map("tag:tags" -> Seq("testing", "diabetes", "PublishedWorkspaceSpec"))
        val curatorUser = UserPool.chooseCurator
        implicit val authToken: AuthToken = curatorUser.makeAuthToken()

        withTemporaryBillingProject(billingAccountId, users = List(curatorUser.email).some) { billingProject =>
          withWorkspace(billingProject, "PublishedWorkspaceSpec_tags", attributes = Some(tags)) { workspaceName =>

            val data = LibraryData.metadataBasic + ("library:datasetName" -> workspaceName)
            Orchestration.library.setLibraryAttributes(billingProject, workspaceName, data)
            Orchestration.library.publishWorkspace(billingProject, workspaceName)

            withClue("find tag:tags in library dataset") {
              eventually {
                val codes = getDatasetFieldValues(workspaceName, "tag:tags")
                codes should contain theSameElementsAs List("PublishedWorkspaceSpec", "testing", "diabetes")
              }
            }
          }
        }(UserPool.chooseProjectOwner.makeAuthToken(billingScopes))
      }
    }
  }


  /**
    *
    * @return True: workspace is visible in library table
    */
  private def isVisibleInLibrary(workspaceName: String)(implicit token: AuthToken): Boolean = {
    import DefaultJsonProtocol._
    val response = Orchestration.library.searchPublishedLibraryDataset(workspaceName)
    val total = JsonParser(response).asJsObject.fields("total").convertTo[Int]
    total == 1
  }

  private def getDatasetFieldValues(workspaceName: String, fieldName: String)(implicit token: AuthToken): List[String] = {
    import DefaultJsonProtocol._
    val datasetMap: String = Orchestration.library.searchPublishedLibraryDataset(workspaceName)
    val results: JsValue = datasetMap.parseJson.asJsObject.fields("results")
    val codes: JsValue = results.asInstanceOf[JsArray].elements.head.asJsObject.fields(fieldName)
    codes.convertTo[List[String]]
  }

}
