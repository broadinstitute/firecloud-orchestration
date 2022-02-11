package org.broadinstitute.dsde.test.api.orch

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.test.OrchConfig
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures.withCleanBillingProject
import org.broadinstitute.dsde.workbench.service.BillingProject.BillingProjectStatus
import org.broadinstitute.dsde.workbench.service.Orchestration.NIH.NihDatasetPermission
import org.broadinstitute.dsde.workbench.service.test.CleanUp
import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}

import java.time.Instant
import java.util.UUID

class OrchestrationApiSpec
  extends FreeSpec
    with Matchers
    with ScalaFutures
    with Eventually
    with CleanUp {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  def nowPrint(text: String): Unit = {
    println(s"${Instant.now} : $text")
  }

  val billingAcccountName: String = "billing-account-name"

  "Orchestration" - {
    "should link an eRA Commons account with access to the TARGET closed-access dataset" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetJsonWebTokenKey)

        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", true)))
      }
    }

    "should link an eRA Commons account with access to the TCGA closed-access dataset" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.tcgaJsonWebTokenKey)

        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", false)))
      }
    }

    "should link an eRA Commons account with access to none of the supported closed-access datasets" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)

        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false)))
      }
    }

    "should sync the whitelist and remove a user who no longer has access to either closed-access dataset" in {
      withCleanUp {
        val user = UserPool.chooseAuthDomainUser
        implicit val userToken: AuthToken = user.makeAuthToken()

        register cleanUp resetNihLinkToInactive

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetAndTcgaJsonWebTokenKey)

        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", true)))

        Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)

        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false)))
      }
    }

    "should get the user's billing projects" in {
      val ownerUser: Credentials = UserPool.chooseProjectOwner
      implicit val ownerToken: AuthToken = ownerUser.makeAuthToken()
      withCleanBillingProject(billingAcccountName) { projectName =>
        nowPrint(s"Querying for (owner) user profile billing projects, with project: $projectName")
        // Returns a list of maps, one map per billing project the user has access to.
        // Each map has a key for the projectName, role, status, and an optional message
        val projectList: List[Map[String, String]] = Orchestration.profile.getUserBillingProjects()
        val projectNames: List[String] = projectList.map(_.getOrElse("projectName", ""))
        projectNames should (contain(projectName) and not contain "")
      }
    }

    "querying for an individual billing project status" - {

      "should get the user's billing project" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        implicit val ownerToken: AuthToken = ownerUser.makeAuthToken()
        withCleanBillingProject(billingAcccountName) { projectName =>
          nowPrint(s"Querying for (owner) user profile billing project status: $projectName")
          // Returns a map of projectName and creationStatus for the project specified
          implicit val patienceConfig = PatienceConfig(Span(2, Minutes), Span(10, Seconds))
          eventually {
            val statusMap: Map[String, String] = Orchestration.profile.getUserBillingProjectStatus(projectName)
            statusMap should contain("projectName" -> projectName)
            statusMap should contain("creationStatus" -> BillingProjectStatus.Ready.toString)
          }
        }
      }

      "should not find a non-existent billing project" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        implicit val ownerToken: AuthToken = ownerUser.makeAuthToken()
        withCleanBillingProject(billingAcccountName) { projectName =>
          val random: String = UUID.randomUUID().toString
          nowPrint(s"Querying for (owner) user profile billing project status: $random")
          val getException = intercept[RestException] {
            Orchestration.profile.getUserBillingProjectStatus(random)
          }
          getException.message should include(StatusCodes.NotFound.defaultMessage)
        }
      }

      "should not find a billing project for user without billing project access" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        val user: Credentials = UserPool.chooseStudent
        val userToken: AuthToken = user.makeAuthToken()
        withCleanBillingProject(billingAcccountName) { projectName =>
          nowPrint(s"Querying for (student) user profile billing project status: $projectName")
          val getException = intercept[RestException] {
            Orchestration.profile.getUserBillingProjectStatus(projectName)(userToken)
          }
          getException.message should include(StatusCodes.NotFound.defaultMessage)
        }(ownerUser.makeAuthToken())
      }
    }
  }

  //We need to reset the user's link to a state where it doesn't have access to any of the datasets
  //To do so, we will link with a JWT that doesn't have access to any datasets and then sync both whitelists
  private def resetNihLinkToInactive()(implicit authToken: AuthToken) = {
    Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)

    Orchestration.NIH.syncWhitelistFull

    verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false)))
  }

  private def verifyDatasetPermissions(expectedPermissions: Set[NihDatasetPermission])(implicit authToken: AuthToken) = {
    // Sam caches group membership for a minute (but not in fiab) so may need to wait
    implicit val patienceConfig = PatienceConfig(Span(2, Minutes), Span(10, Seconds))
    eventually {
      Orchestration.NIH.getUserNihStatus.datasetPermissions should contain allElementsOf expectedPermissions
    }
  }
}