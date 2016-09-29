package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.Application
import org.broadinstitute.dsde.firecloud.core.NIHWhitelistUtils
import org.broadinstitute.dsde.firecloud.dataaccess.{MockRawlsDAO, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddUpdateAttribute, RemoveAttribute}
import org.broadinstitute.dsde.firecloud.model.{AttributeString, UserInfo}
import org.broadinstitute.dsde.firecloud.utils.DateUtils
import org.scalatest.FreeSpec
import spray.http.OAuth2BearerToken

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import spray.json._

class LibraryServiceSpec extends FreeSpec {

  /*
  val rawlsDAO:RawlsDAO = new MockRawlsDAO
  val mockUserInfo = UserInfo(userEmail="nobody@nowhere.org", accessToken=OAuth2BearerToken("faketoken"), accessTokenExpiresIn=System.currentTimeMillis(), id="1111111111")
  */
  val existingAttrs1 = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "library:keythree"->"valthree", "library:keyfour"->"valfour")

  "LibraryService" - {
    "when new attrs are empty" - {
      "should calculate all attribute removals" in {
        val newAttrs = """{}""".parseJson
        val expected = Seq(
          RemoveAttribute("library:keyone"),
          RemoveAttribute("library:keytwo"),
          RemoveAttribute("library:keythree"),
          RemoveAttribute("library:keyfour")
        )
        assertResult(expected) {
          LibraryService.generateAttributeOperations(existingAttrs1, newAttrs.asJsObject)
        }
      }
    }
    "when new attrs are a subset" - {
      "should calculate removals and updates" in {
        val newAttrs = """{"library:keyone":"valoneNew", "library:keytwo":"valtwoNew"}""".parseJson
        val expected = Seq(
          RemoveAttribute("library:keythree"),
          RemoveAttribute("library:keyfour"),
          AddUpdateAttribute("library:keyone",AttributeString("valoneNew")),
          AddUpdateAttribute("library:keytwo",AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          LibraryService.generateAttributeOperations(existingAttrs1, newAttrs.asJsObject)
        }
      }
    }
  }
}
