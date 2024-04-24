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
    "should link an eRA Commons account with access to the TARGET closed-access dataset" ignore {
      val user = UserPool.chooseAuthDomainUser
      implicit val userToken: AuthToken = user.makeAuthToken()

      Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetJsonWebTokenKey)
      try verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", true)))
      finally resetNihLinkToInactive()
    }

    "should link an eRA Commons account with access to the TCGA closed-access dataset" ignore {
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

    "should sync the whitelist and remove a user who no longer has access to either closed-access dataset" ignore {
      val user = UserPool.chooseAuthDomainUser
      implicit val userToken: AuthToken = user.makeAuthToken()

      Orchestration.NIH.addUserInNIH(OrchConfig.Users.targetAndTcgaJsonWebTokenKey)
      try {
        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", true), NihDatasetPermission("TARGET", true)))
        Orchestration.NIH.addUserInNIH(OrchConfig.Users.genericJsonWebTokenKey)
        verifyDatasetPermissions(Set(NihDatasetPermission("TCGA", false), NihDatasetPermission("TARGET", false)))
      } finally resetNihLinkToInactive()
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
