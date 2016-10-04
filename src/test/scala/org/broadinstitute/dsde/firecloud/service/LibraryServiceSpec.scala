package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, _}
import org.broadinstitute.dsde.firecloud.model.{AttributeString, UserInfo}
import org.scalatest.FreeSpec
import spray.json._

import scala.concurrent.ExecutionContext

class LibraryServiceSpec extends FreeSpec with LibraryServiceSupport {

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
          generateAttributeOperations(existingAttrs1, newAttrs.asJsObject)
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
          generateAttributeOperations(existingAttrs1, newAttrs.asJsObject)
        }
      }
    }
    "when new attrs contain an array" - {
      "should calculate list updates" in {
        val newAttrs = """{"library:keyone":"valoneNew", "library:keytwo":["valtwoA","valtwoB","valtwoC"]}""".parseJson
        val expected = Seq(
          RemoveAttribute("library:keythree"),
          RemoveAttribute("library:keyfour"),
          RemoveAttribute("library:keytwo"),
          AddUpdateAttribute("library:keyone",AttributeString("valoneNew")),
          AddListMember("library:keytwo",AttributeString("valtwoA")),
          AddListMember("library:keytwo",AttributeString("valtwoB")),
          AddListMember("library:keytwo",AttributeString("valtwoC"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingAttrs1, newAttrs.asJsObject)
        }
      }
    }
  }
}
