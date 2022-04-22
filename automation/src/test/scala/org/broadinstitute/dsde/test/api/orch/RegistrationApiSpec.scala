package org.broadinstitute.dsde.test.api.orch

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.testkit.TestKitBase
import org.broadinstitute.dsde.test.OrchConfig.Users
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.service.Sam.sendRequest
import org.broadinstitute.dsde.workbench.service.util.ExceptionHandling
import org.broadinstitute.dsde.workbench.service.{Orchestration, Sam, Thurloe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Retryable
import org.scalatest.time.{Seconds, Span}


class RegistrationApiSpec
  extends AnyFreeSpec
    with Matchers
    with ScalaFutures
    with Eventually
    with BeforeAndAfterAll
    with TestKitBase
    with ExceptionHandling {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))
  implicit lazy val system = ActorSystem()

  val subjectId: String = Users.tempSubjectId
  val adminUser: Credentials = UserPool.chooseAdmin
  implicit val authToken: AuthToken = adminUser.makeAuthToken()

  override protected def beforeAll() = {
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
      userInfo.enabled shouldBe true
    }

    "should return terms of services with no auth token" in {
      val req = HttpRequest(GET, ServiceTestConfig.FireCloud.orchApiUrl + s"tos/text")
      val response = sendRequest(req)

      val textFuture = Unmarshal(response.entity).to[String]

      response.status shouldEqual StatusCodes.OK
      whenReady(textFuture) { text =>
        text.isEmpty() shouldBe false
      }
    }
  }
}
