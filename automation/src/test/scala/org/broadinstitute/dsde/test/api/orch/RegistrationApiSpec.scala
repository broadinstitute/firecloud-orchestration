package org.broadinstitute.dsde.test.api.orch

import org.broadinstitute.dsde.test.OrchConfig.Users
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.service.Orchestration
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.service.{Sam, Thurloe}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.tagobjects.Retryable
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterEach, FreeSpec, Matchers}

class RegistrationApiSpec extends FreeSpec with Matchers with ScalaFutures with Eventually
  with BillingFixtures with BeforeAndAfter {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  val subjectId: String = Users.tempSubjectId
  val adminUser: Credentials = UserPool.chooseAdmin
  implicit val authToken: AuthToken = adminUser.makeAuthToken()

  before {
    if (Sam.admin.doesUserExist(subjectId).getOrElse(false)) {
      try {
        Sam.admin.deleteUser(subjectId)
      } catch nonFatalAndLog("Error deleting user before test but will try running the test anyway")
    }
    Thurloe.keyValuePairs.deleteAll(subjectId)
  }

  "FireCloud registration" - {

    "should allow a person to register" taggedAs Retryable in {
      val user = UserPool.userConfig.Temps.getUserCredential("luna")
      implicit val authToken: AuthToken = user.makeAuthToken()

      val basicUser = Orchestration.profile.BasicProfile(
        "Test",
        "Dummy",
        "Tester",
        Some("test@firecloud.org"),
        "Broad",
        "DSDE",
        "Cambridge",
        "MA",
        "USA",
        "Nobody",
        "true"
      )

      Orchestration.profile.registerUser(basicUser)

      val userInfo = Sam.user.getUserStatusInfo()(authToken).get
      userInfo.userEmail should include (user.email)
      userInfo.userSubjectId should include (subjectId)
      userInfo.enabled shouldBe true
    }
  }
}