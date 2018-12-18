package org.broadinstitute.dsde.test.api.orch

import java.time.Instant
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.bigquery.BigqueryScopes
import com.google.api.services.bigquery.model.{GetQueryResultsResponse, JobReference}
import org.broadinstitute.dsde.test.OrchConfig
import org.broadinstitute.dsde.workbench.auth.{AuthToken, AuthTokenScopes}
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.dao.Google.googleBigQueryDAO
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.service.Orchestration.NIH.NihDatasetPermission

class OrchestrationApiSpec extends FreeSpec with Matchers with ScalaFutures with Eventually
  with BillingFixtures {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  def nowPrint(text: String): Unit = {
    println(s"${Instant.now} : $text")
  }

  "Orchestration" - {
    /**
      * This next test is a bit funky. It tests the system's ability to grant/remove Google roles ... and it does so
      * by trying to execute a BigQuery job as the current user (which should fail), granting the appropriate role
      * to that user and watching the BQ job succeed, then removing the role and again seeing the BQ job fail.
      *
      * The funkiness is that:
      *   - FireCloud never executes BQ jobs as the end user
      *   - to execute a BQ job as the end user, FireCloud would have to grant the CLOUD_PLATFORM OAuth scope to that
      *       user, which we also never do in the real app.
      *
      * HOWEVER, apps outside of FireCloud show activity to this API, so we should continue to maintain and test it.
      * The test does check the right things, even if FireCloud itself doesn't use it.
      */
    "should grant and remove google role access" in {
      // google roles can take a while to take effect
      implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(1, Minutes)), interval = scaled(Span(10, Seconds)))

      val ownerUser: Credentials = UserPool.chooseProjectOwner
      val ownerToken: AuthToken = ownerUser.makeAuthToken()
      val role = "bigquery.jobUser"

      val user: Credentials = UserPool.chooseStudent
      val userToken: AuthToken = user.makeAuthToken(AuthTokenScopes.userLoginScopes :+ BigqueryScopes.CLOUD_PLATFORM)
      val bigQuery = googleBigQueryDAO(userToken)

      // Willy Shakes uses this insult twice

      val shakespeareQuery = "SELECT COUNT(*) AS scullion_count FROM publicdata.samples.shakespeare WHERE word='scullion'"

      def assertExpectedShakespeareResult(response: GetQueryResultsResponse): Unit = {
        val resultRows = response.getRows
        resultRows.size shouldBe 1
        val resultFields = resultRows.get(0).getF
        resultFields.size shouldBe 1
        resultFields.get(0).getV.toString shouldBe "2"
      }

      nowPrint("Begin Test")

      withCleanBillingProject(ownerUser) { projectName =>

        nowPrint("Project created")

        val preRoleFailure = bigQuery.startQuery(GoogleProject(projectName), "meh").failed.futureValue

        preRoleFailure shouldBe a[GoogleJsonResponseException]
        preRoleFailure.getMessage should include(user.email)
        preRoleFailure.getMessage should include(projectName)
        preRoleFailure.getMessage should include("bigquery.jobs.create")

        nowPrint("Passed - user can't query in new project")

        Orchestration.billing.addGoogleRoleToBillingProjectUser(projectName, user.email, role)(ownerToken)

        nowPrint("Role granted")

        // The google role might not have been applied the first time we call startQuery() - poll until it has
        val queryReference = eventually {
          val start = bigQuery.startQuery(GoogleProject(projectName), shakespeareQuery).futureValue
          start shouldBe a[JobReference]
          start
        }

        nowPrint("Passed - user can start query after role granted")

        // poll for query status until it completes
        val queryJob = eventually {
          val job = bigQuery.getQueryStatus(queryReference).futureValue
          job.getStatus.getState shouldBe "DONE"
          job
        }

        val queryResult = bigQuery.getQueryResult(queryJob).futureValue
        assertExpectedShakespeareResult(queryResult)

        nowPrint("Passed - query completed")

        Orchestration.billing.removeGoogleRoleFromBillingProjectUser(projectName, user.email, role)(ownerToken)

        nowPrint("Role denied")

        // The google role might not have been removed the first time we call startQuery() - poll until it has
        val postRoleFailure = eventually {
          val failure = bigQuery.startQuery(GoogleProject(projectName), shakespeareQuery).failed.futureValue
          failure shouldBe a[GoogleJsonResponseException]
          failure
        }

        postRoleFailure.getMessage should include(user.email)
        postRoleFailure.getMessage should include(projectName)
        postRoleFailure.getMessage should include("bigquery.jobs.create")

        nowPrint("Passed - user can't query after role denied")
      }

      nowPrint("End Test")
    }

    "should not allow access alteration for arbitrary google roles" in {
      val ownerUser: Credentials = UserPool.chooseProjectOwner
      val ownerToken: AuthToken = ownerUser.makeAuthToken()
      val roles = Seq("bigquery.admin", "bigquery.dataEditor", "bigquery.user")

      val user: Credentials = UserPool.chooseStudent

      withCleanBillingProject(ownerUser) { projectName =>
        roles foreach { role =>
          val addEx = intercept[RestException] {
            Orchestration.billing.addGoogleRoleToBillingProjectUser(projectName, user.email, role)(ownerToken)
          }
          addEx.getMessage should include(role)

          val removeEx = intercept[RestException] {
            Orchestration.billing.removeGoogleRoleFromBillingProjectUser(projectName, user.email, role)(ownerToken)
          }
          removeEx.getMessage should include(role)
        }
      }
    }

    "should not allow access alteration by non-owners" in {
      val Seq(userA: Credentials, userB: Credentials) = UserPool.chooseStudents(2)
      val userAToken: AuthToken = userA.makeAuthToken()

      val role = "bigquery.jobUser"
      val errorMsg = "You must be a project owner"
      val unownedProject = "broad-dsde-dev"

      val addEx = intercept[RestException] {
        Orchestration.billing.addGoogleRoleToBillingProjectUser(unownedProject, userB.email, role)(userAToken)
      }
      addEx.getMessage should include(errorMsg)
      addEx.getMessage should include(StatusCodes.Forbidden.intValue.toString)

      val removeEx = intercept[RestException] {
        Orchestration.billing.removeGoogleRoleFromBillingProjectUser(unownedProject, userB.email, role)(userAToken)
      }
      removeEx.getMessage should include(errorMsg)
      removeEx.getMessage should include(StatusCodes.Forbidden.intValue.toString)
    }

    "should link an eRA Commons account with access to the TARGET closed-access dataset" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetJsonWebTokenKey)

        Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", true))
      }
    }

    "should link an eRA Commons account with access to the TCGA closed-access dataset" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.tcgaJsonWebTokenKey)

        Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", false))
      }
    }

    "should link an eRA Commons account with access to none of the supported closed-access datasets" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)

        Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false))
      }
    }

    "should sync the whitelist and remove a user who no longer has access to either closed-access dataset" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetAndTcgaJsonWebTokenKey)

        Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", true))

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)

        Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", true))

        Orchestration.NIH.syncWhitelistFull

        Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false))
      }
    }

    "should get the user's billing projects" in {
      val ownerUser: Credentials = UserPool.chooseProjectOwner
      val ownerToken: AuthToken = ownerUser.makeAuthToken()
      withCleanBillingProject(ownerUser) { projectName =>
        nowPrint(s"Querying for (owner) user profile billing projects, with project: $projectName")
        // Returns a list of maps, one map per billing project the user has access to.
        // Each map has a key for the projectName, role, status, and an optional message
        val projectList: List[Map[String, String]] = Orchestration.profile.getUserBillingProjects()(ownerToken)
        val projectNames: List[String] = projectList.map(_.getOrElse("projectName", ""))
        projectNames should (contain(projectName) and not contain "")
      }
    }

    "querying for an individual billing project status" - {

      "should get the user's billing project" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        val ownerToken: AuthToken = ownerUser.makeAuthToken()
        withCleanBillingProject(ownerUser) { projectName =>
          nowPrint(s"Querying for (owner) user profile billing project status: $projectName")
          // Returns a map of projectName and creationStatus for the project specified
          val statusMap: Map[String, String] = Orchestration.profile.getUserBillingProjectStatus(projectName)(ownerToken)
          statusMap should contain ("projectName" -> projectName)
          statusMap should contain ("creationStatus" -> Orchestration.billing.BillingProjectStatus.Ready.toString)
        }
      }

      "should not find a non-existent billing project" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        val ownerToken: AuthToken = ownerUser.makeAuthToken()
        withCleanBillingProject(ownerUser) { projectName =>
          val random: String = UUID.randomUUID().toString
          nowPrint(s"Querying for (owner) user profile billing project status: $random")
          val getException = intercept[RestException] {
            Orchestration.profile.getUserBillingProjectStatus(random)(ownerToken)
          }
          getException.message should include(StatusCodes.NotFound.defaultMessage)
        }
      }

      "should not find a billing project for user without billing project access" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        val user: Credentials = UserPool.chooseStudent
        val userToken: AuthToken = user.makeAuthToken()
        withCleanBillingProject(ownerUser) { projectName =>
          nowPrint(s"Querying for (student) user profile billing project status: $projectName")
          val getException = intercept[RestException] {
            Orchestration.profile.getUserBillingProjectStatus(projectName)(userToken)
          }
          getException.message should include(StatusCodes.NotFound.defaultMessage)
        }
      }
    }
  }

  //We need to reset the user's link to a state where it doesn't have access to any of the datasets
  //To do so, we will link with a JWT that doesn't have access to any datasets and then sync both whitelists
  private def resetNihLinkToInactive()(implicit authToken: AuthToken) = {
    Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)

    Orchestration.NIH.syncWhitelistFull

    // Sam caches group membership for a minute (but not in fiab) so may need to wait
    implicit val patienceConfig = PatienceConfig(Span(2, Minutes), Span(10, Seconds))
    eventually {
      Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false))
    }
  }
}