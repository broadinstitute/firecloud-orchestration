package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, _}
import org.broadinstitute.dsde.firecloud.model.{AttributeString, UserInfo}
import org.scalatest.FreeSpec
import spray.json._

import scala.concurrent.ExecutionContext

class LibraryServiceSpec extends FreeSpec with LibraryServiceSupport {

  val existingLibraryAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "library:keythree"->"valthree", "library:keyfour"->"valfour")
  val existingMixedAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour")
  val existingPublishedAttrs = Map("library:published"->"true", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour")

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
          generateAttributeOperations(existingLibraryAttrs, newAttrs.asJsObject)
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
          generateAttributeOperations(existingLibraryAttrs, newAttrs.asJsObject)
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
          AddUpdateAttribute("library:keyone", AttributeString("valoneNew")),
          AddListMember("library:keytwo", AttributeString("valtwoA")),
          AddListMember("library:keytwo", AttributeString("valtwoB")),
          AddListMember("library:keytwo", AttributeString("valtwoC"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs.asJsObject)
        }
      }
    }
    "when old attrs include non-library" - {
      "should not touch old non-library attrs" in {
        val newAttrs = """{"library:keyone":"valoneNew"}""".parseJson
        val expected = Seq(
          RemoveAttribute("library:keytwo"),
          AddUpdateAttribute("library:keyone",AttributeString("valoneNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingMixedAttrs, newAttrs.asJsObject)
        }
      }
    }
    "when new attrs include non-library" - {
      "should not touch new non-library attrs" in {
        val newAttrs = """{"library:keyone":"valoneNew", "library:keytwo":"valtwoNew", "333":"three", "444":"four"}""".parseJson
        val expected = Seq(
          RemoveAttribute("library:keythree"),
          RemoveAttribute("library:keyfour"),
          AddUpdateAttribute("library:keyone",AttributeString("valoneNew")),
          AddUpdateAttribute("library:keytwo",AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs.asJsObject)
        }
      }
    }
    "when old attrs include published flag" - {
      "should not touch old published flag" in {
        val newAttrs = """{"library:keyone":"valoneNew"}""".parseJson
        val expected = Seq(
          RemoveAttribute("library:keytwo"),
          AddUpdateAttribute("library:keyone",AttributeString("valoneNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingPublishedAttrs, newAttrs.asJsObject)
        }
      }
    }
    "when new attrs include published flag" - {
      "should not touch old published flag" in {
        val newAttrs = """{"library:published":"true","library:keyone":"valoneNew", "library:keytwo":"valtwoNew"}""".parseJson
        val expected = Seq(
          RemoveAttribute("library:keythree"),
          RemoveAttribute("library:keyfour"),
          AddUpdateAttribute("library:keyone",AttributeString("valoneNew")),
          AddUpdateAttribute("library:keytwo",AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs.asJsObject)
        }
      }
    }
    "when publishing a workspace" - {
      "should add a library:published attribute" in {
        val expected = Seq(AddUpdateAttribute("library:published",AttributeString("true")))
        assertResult(expected) {
          updatePublishAttribute(true)
        }
      }
    }
    "when unpublishing a workspace" - {
      "should remove the library:published attribute" in {
        val expected = Seq(RemoveAttribute("library:published"))
        assertResult(expected) {
          updatePublishAttribute(false)
        }
      }
    }
  }
}
