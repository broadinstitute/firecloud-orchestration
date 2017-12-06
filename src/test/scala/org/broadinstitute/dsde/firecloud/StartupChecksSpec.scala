package org.broadinstitute.dsde.firecloud

import org.broadinstitute.dsde.firecloud.dataaccess.MockSamDAO
import org.broadinstitute.dsde.firecloud.model.{RegistrationInfo, WithAccessToken, WorkbenchEnabled, WorkbenchUserInfo}
import org.broadinstitute.dsde.firecloud.service.BaseServiceSpec
import org.broadinstitute.dsde.rawls.model.ErrorReport

import spray.http.StatusCodes.NotFound

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class StartupChecksSpec extends BaseServiceSpec {

  // brute-force logging off for the StartupChecks class while running this test
  org.slf4j.LoggerFactory.getLogger(new StartupChecks(app).getClass)
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  "Startup checks" - {
    val tokens = Map(
      "admin SA" -> app.googleServicesDAO.getAdminUserAccessToken,
      "billing SA" -> app.googleServicesDAO.getTrialBillingManagerAccessToken
    )

    "should pass if all SAs are registered" in {
      val testApp = app.copy(samDAO = new StartupChecksMockSamDAO)
      val check = Await.result(new StartupChecks(testApp).check, 3.minutes)
      assert(check)
    }

    tokens.foreach { case (name, token) =>
      s"should fail if $name is not ldap-enabled" in {
        val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
          unregisteredTokens = Seq(token)))
        val check = Await.result(new StartupChecks(testApp).check, 3.minutes)
        assert(!check)
      }
    }
    "should fail if all SAs are not ldap-enabled" in {
      val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
        unregisteredTokens = tokens.values.toSeq))
      val check = Await.result(new StartupChecks(testApp).check, 3.minutes)
      assert(!check)
    }

    tokens.foreach { case (name, token) =>
      s"should fail if $name is not registered at all (404)" in {
        val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
          notFounds = Seq(token)))
        val check = Await.result(new StartupChecks(testApp).check, 3.minutes)
        assert(!check)
      }
    }
    "should fail if all SAs are not registered at all (404)" in {
      val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
        notFounds = tokens.values.toSeq))
      val check = Await.result(new StartupChecks(testApp).check, 3.minutes)
      assert(!check)
    }

    tokens.foreach { case (name, token) =>
      s"should fail if $name returns unexpected error)" in {
        val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
          unexpectedErrors = Seq(token)))
        val check = Await.result(new StartupChecks(testApp).check, 3.minutes)
        assert(!check)
      }
    }
    "should fail if all SAs return unexpected error" in {
      val testApp = app.copy(samDAO = new StartupChecksMockSamDAO(
        unexpectedErrors = tokens.values.toSeq))
      val check = Await.result(new StartupChecks(testApp).check, 3.minutes)
      assert(!check)
    }

  }
}

class StartupChecksMockSamDAO(unregisteredTokens:Seq[String] = Seq.empty[String],
                              notFounds:Seq[String] = Seq.empty[String],
                              unexpectedErrors:Seq[String] = Seq.empty[String]) extends MockSamDAO {

  override def getRegistrationStatus(implicit userInfo: WithAccessToken): Future[RegistrationInfo] = {
    if (notFounds.contains(userInfo.accessToken.token)) {
      Future.failed(new FireCloudExceptionWithErrorReport(ErrorReport(NotFound, "unit test intentional failure")))
    } else {
      if (unexpectedErrors.contains(userInfo.accessToken.token)) {
        Future.failed(new Exception("unit test generic error"))
      } else {
        val isRegistered = !unregisteredTokens.contains(userInfo.accessToken.token)
        Future.successful(RegistrationInfo(
          WorkbenchUserInfo(userSubjectId = "foo", userEmail = "bar"),
          WorkbenchEnabled(google = true, ldap = isRegistered, allUsersGroup = true)))
      }
    }
  }
}
