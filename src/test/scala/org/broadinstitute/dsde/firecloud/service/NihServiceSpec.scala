package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess._
import org.broadinstitute.dsde.firecloud.mock.MockGoogleServicesDAO
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 3/7/17.
  */
class NihServiceSpec extends FlatSpec with Matchers {

  implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  val samDao = new MockSamDAO
  val thurloeDao = new MockThurloeDAO
  val googleDao = new MockGoogleServicesDAO
  val shibbolethDao = new MockShibbolethDAO

  // build the service instance we'll use for tests
  val nihService = new NihService(samDao, thurloeDao, googleDao, shibbolethDao)

  val usernames = Map("fcSubjectId1" -> "nihUsername1", "fcSubjectId2" -> "nihUsername2")

  val expiretimes1 = Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString, "fcSubjectId2" -> DateUtils.nowPlus24Hours.toString)
  val currentUsernames1 = Map("fcSubjectId2" -> "nihUsername2")

  val expiretimes2 = Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString)
  val expiretimes3 = Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString, "fcSubjectId2" -> "not a number")

  "NihService" should "only include unexpired users when handling expired and unexpired users" in {
    assertResult(currentUsernames1) {
      nihService.filterForCurrentUsers(usernames, expiretimes1)
    }
  }

  it should "not include users with no expiration times" in {
    assertResult(Map()) {
      nihService.filterForCurrentUsers(usernames, expiretimes2)
    }
  }

  it should "not include users with unparseable expiration times" in {
    assertResult(Map()) {
      nihService.filterForCurrentUsers(usernames, expiretimes3)
    }
  }
}
