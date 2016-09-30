package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations._
import org.broadinstitute.dsde.firecloud.model.AttributeString
import org.scalatest.FreeSpec
import spray.json._

class LibraryServiceSpec extends FreeSpec {

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
    "when publishing a workspace" - {
      "should add a library:published attribute" in {
        val expected = Seq(AddUpdateAttribute("library:published",AttributeString("true")))
        assertResult(expected) {
          LibraryService.updatePublishAttribute(true)
        }
      }
    }
    "when unpublishing a workspace" - {
      "should remove the library:published attribute" in {
        val expected = Seq(RemoveAttribute("library:published"))
        assertResult(expected) {
          LibraryService.updatePublishAttribute(false)
        }
      }
    }
  }
}
