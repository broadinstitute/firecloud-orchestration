package org.broadinstitute.dsde.firecloud.test.api.orch

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.bigquery.model.{GetQueryResultsResponse, JobReference}
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Config, Credentials, UserPool}
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
    "should grant and remove google role access" in {
      // google roles can take a while to take effect
      implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(1, Minutes)), interval = scaled(Span(10, Seconds)))

      val ownerUser: Credentials = UserPool.chooseProjectOwner
      val ownerToken: AuthToken = ownerUser.makeAuthToken()
      val role = "bigquery.jobUser"

      val user: Credentials = UserPool.chooseStudent
      val userToken: AuthToken = user.makeAuthToken()
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

    "should link an eRA Commons account with access to TARGET closed-access data" in {
      val user = UserPool.chooseAuthDomainUser
      val userToken: AuthToken = user.makeAuthToken()

      Orchestration.NIH.addUserInNIH(Config.Users.targetJsonWebTokenKey)

      Orchestration.NIH.getUserNihStatus.datasetPermissions should contain theSameElementsAs Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", true))
    }

    "should link an eRA Commons account with access to TCGA closed-access data" in {
      val user = UserPool.chooseAuthDomainUser
      val userToken: AuthToken = user.makeAuthToken()

      Orchestration.NIH.addUserInNIH(Config.Users.tcgaJsonWebTokenKey)

      Orchestration.NIH.getUserNihStatus.datasetPermissions should contain theSameElementsAs Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", false))
    }

    "should sync the whitelist and remove a user who no longer has access to either closed-access dataset" in {
      val user = UserPool.chooseAuthDomainUser
      val userToken: AuthToken = user.makeAuthToken()
      
      Orchestration.NIH.addUserInNIH(Config.Users.targetAndTcgaJsonWebTokenKey)

      Orchestration.NIH.getUserNihStatus.datasetPermissions should contain theSameElementsAs Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", true))

      Orchestration.NIH.addUserInNIH(Config.Users.genericJsonWebTokenKey)

      Orchestration.NIH.getUserNihStatus.datasetPermissions should contain theSameElementsAs Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", true))

      Orchestration.NIH.syncWhitelistFull

      Orchestration.NIH.getUserNihStatus.datasetPermissions should contain theSameElementsAs Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false))
    }

  }
}