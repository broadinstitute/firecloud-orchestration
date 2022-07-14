package org.broadinstitute.dsde.test.api.orch


import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.test.OrchConfig
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.auth.AuthTokenScopes.billingScopes
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures.withTemporaryBillingProject
import org.broadinstitute.dsde.workbench.service.BillingProject.BillingProjectStatus
import org.broadinstitute.dsde.workbench.service.Orchestration.NIH.NihDatasetPermission
import org.broadinstitute.dsde.workbench.service.{Orchestration, RestException}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Minutes, Seconds, Span}

import java.time.Instant
import java.util.UUID


class OrchestrationApiSpec
  extends AnyFreeSpec
    with Matchers
    with ScalaFutures
    with Eventually {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))
  val billingAccountId: String = ServiceTestConfig.Projects.billingAccountId

  def nowPrint(text: String): Unit = {
    println(s"${Instant.now} : $text")
  }

  "Orchestration" - {
    "should link an eRA Commons account with access to the TARGET closed-access dataset" in {
      val user = UserPool.chooseAuthDomainUser
      implicit val userToken: AuthToken = user.makeAuthToken()

      Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetJsonWebTokenKey)
      try verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", true)))
      finally resetNihLinkToInactive()
    }

    "should link an eRA Commons account with access to the TCGA closed-access dataset" in {
      val user = UserPool.chooseAuthDomainUser
      implicit val userToken: AuthToken = user.makeAuthToken()

      Orchestration.NIH.addUserInNIH(OrchConfig.Users.tcgaJsonWebTokenKey)
      try verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", false)))
      finally resetNihLinkToInactive()
    }

  "should link an eRA Commons account with access to none of the supported closed-access datasets" in {
    val user = UserPool.chooseAuthDomainUser
    implicit val userToken: AuthToken = user.makeAuthToken()

    Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)
    try verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false)))
    finally resetNihLinkToInactive()
  }

    "should sync the whitelist and remove a user who no longer has access to either closed-access dataset" in {
      val user = UserPool.chooseAuthDomainUser
      implicit val userToken: AuthToken = user.makeAuthToken()

      Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetAndTcgaJsonWebTokenKey)
      try {
        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", true)))
        Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)
        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false)))
      } finally resetNihLinkToInactive()
    }

    "should get the user's billing projects" in {
      val ownerUser: Credentials = UserPool.chooseProjectOwner
      val ownerToken: AuthToken = ownerUser.makeAuthToken()
      withTemporaryBillingProject(billingAccountId) { projectName =>
        nowPrint(s"Querying for (owner) user profile billing projects, with project: $projectName")
        // Returns a list of maps, one map per billing project the user has access to.
        // Each map has a key for the projectName, role, status, and an optional message
        val projectList: List[Map[String, String]] = Orchestration.profile.getUserBillingProjects()(ownerToken)
        val projectNames: List[String] = projectList.map(_.getOrElse("projectName", ""))
        projectNames should (contain(projectName) and not contain "")
      }(ownerUser.makeAuthToken(billingScopes))
    }

    "querying for an individual billing project status" - {

      "should get the user's billing project" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        val ownerToken: AuthToken = ownerUser.makeAuthToken()
        withTemporaryBillingProject(billingAccountId) { projectName =>
          nowPrint(s"Querying for (owner) user profile billing project status: $projectName")
          // Returns a map of projectName and creationStatus for the project specified
          implicit val patienceConfig = PatienceConfig(Span(2, Minutes), Span(10, Seconds))
          eventually {
            val statusMap: Map[String, String] = Orchestration.profile.getUserBillingProjectStatus(projectName)(ownerToken)
            statusMap should contain("projectName" -> projectName)
            statusMap should contain("creationStatus" -> BillingProjectStatus.Ready.toString)
          }
        }(ownerUser.makeAuthToken(billingScopes))
      }

      "should not find a non-existent billing project" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        val ownerToken: AuthToken = ownerUser.makeAuthToken()

        val random: String = UUID.randomUUID().toString
        nowPrint(s"Querying for (owner) user profile billing project status: $random")
        val getException = intercept[RestException] {
          Orchestration.profile.getUserBillingProjectStatus(random)(ownerToken)
        }
        getException.message should include(StatusCodes.NotFound.defaultMessage)
      }

      "should not find a billing project for user without billing project access" in {
        val ownerUser: Credentials = UserPool.chooseProjectOwner
        val user: Credentials = UserPool.chooseStudent
        val userToken: AuthToken = user.makeAuthToken()
        withTemporaryBillingProject(billingAccountId) { projectName =>
          nowPrint(s"Querying for (student) user profile billing project status: $projectName")
          val getException = intercept[RestException] {
            Orchestration.profile.getUserBillingProjectStatus(projectName)(userToken)
          }
          getException.message should include(StatusCodes.NotFound.defaultMessage)
        }(ownerUser.makeAuthToken(billingScopes))
      }
    }
  }

  //We need to reset the user's link to a state where it doesn't have access to any of the datasets
  //To do so, we will link with a JWT that doesn't have access to any datasets and then sync both whitelists
  private def resetNihLinkToInactive()(implicit authToken: AuthToken) = {
    Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)

    Orchestration.NIH.syncWhitelistFull()

    verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false)))
  }

  private def verifyDatasetPermissions(expectedPermissions: Set[NihDatasetPermission])(implicit authToken: AuthToken) = {
    // Sam caches group membership for a minute (but not in fiab) so may need to wait
    implicit val patienceConfig = PatienceConfig(Span(2, Minutes), Span(10, Seconds))
    eventually {
      Orchestration.NIH.getUserNihStatus().datasetPermissions should contain allElementsOf expectedPermissions
    }
  }
}
