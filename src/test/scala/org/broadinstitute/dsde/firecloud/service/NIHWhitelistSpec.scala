package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.core.NIHWhitelistUtils
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.scalatest.FreeSpec
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class NIHWhitelistSpec extends FreeSpec {

  val usernames = Future { Map("fcSubjectId1" -> "nihUsername1", "fcSubjectId2" -> "nihUsername2") }

  val expiretimes1 = Future { Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString, "fcSubjectId2" -> DateUtils.nowPlus24Hours.toString) }
  val currentUsernames1 = Map("fcSubjectId2" -> "nihUsername2")

  val expiretimes2 = Future { Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString) }
  val expiretimes3 = Future { Map("fcSubjectId1" -> DateUtils.nowMinus24Hours.toString, "fcSubjectId2" -> "not a number") }

  "NihWhitelistSpec" - {
    "when handling expired and unexpired users" - {
      "should only include unexpired users" in {
        assertResult(currentUsernames1) {
          Await.result(NIHWhitelistUtils.filterForCurrentUsers(usernames, expiretimes1), 1.second)
        }
      }
    }

    "when handling users with no expiration times" - {
      "should not include them" in {
        assertResult(Map()) {
          Await.result(NIHWhitelistUtils.filterForCurrentUsers(usernames, expiretimes2), 1.second)
        }
      }
    }

    "when handling users with unparseable expiration times" - {
      "should not include them" in {
        assertResult(Map()) {
          Await.result(NIHWhitelistUtils.filterForCurrentUsers(usernames, expiretimes3), 1.second)
        }
      }
    }

  }

}
