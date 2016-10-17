package org.broadinstitute.dsde.firecloud.service

import java.net.URL
import java.util.UUID

import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, _}
import org.broadinstitute.dsde.firecloud.model.{AttributeString, Document, RawlsWorkspace}
import org.parboiled.common.FileUtils
import org.scalatest.FreeSpec
import spray.json._

import scala.util.Try

class LibraryServiceSpec extends FreeSpec with LibraryServiceSupport {

  val existingLibraryAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "library:keythree"->"valthree", "library:keyfour"->"valfour")
  val existingMixedAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour")
  val existingPublishedAttrs = Map("library:published"->"true", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour")

  val testUUID = UUID.randomUUID()

  val testWorkspace = new RawlsWorkspace(workspaceId=testUUID.toString,
    namespace="testWorkspaceNamespace",
    name="testWorkspaceName",
    isLocked=Some(false),
    createdBy="createdBy",
    createdDate="createdDate",
    lastModified=None,
    attributes=Map.empty[String,String],
    bucketName="bucketName",
    accessLevels=Map.empty,
    realm=None)

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
    "with only library attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          "library:foo"->"foo",
          "library:bar"->"bar"
        ))
        val expected = Document(testUUID.toString, Map(
          "library:foo" -> "foo",
          "library:bar" -> "bar",
          "name" -> testWorkspace.name,
          "namespace" -> testWorkspace.namespace,
          "workspaceId" -> testWorkspace.workspaceId
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with only default attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          "baz"->"defaultBaz",
          "qux"->"defaultQux"
        ))
        val expected = Document(testUUID.toString, Map(
          "name" -> testWorkspace.name,
          "namespace" -> testWorkspace.namespace,
          "workspaceId" -> testWorkspace.workspaceId
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with no attributes in workspace" - {
      "should generate indexable document" in {
        // the Map.empty below is currently the same as what's in testWorkspace;
        // include explicitly here in case testWorkspace changes later
        val w = testWorkspace.copy(attributes = Map.empty)
        val expected = Document(testUUID.toString, Map(
          "name" -> testWorkspace.name,
          "namespace" -> testWorkspace.namespace,
          "workspaceId" -> testWorkspace.workspaceId
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with just a (longish) description in workspace" - {
      "should generate indexable document" in {
        // https://hipsum.co/
        val w = testWorkspace.copy(attributes = Map(
          "description"->("Fingerstache copper mug edison bulb, actually austin mustache chartreuse bicycle rights." +
            " Plaid iceland artisan blog street art hammock, subway tile vice. Hammock put a bird on it pinterest tacos" +
            " kitsch gastropub. Chicharrones food truck edison bulb meh. Cardigan aesthetic vegan kitsch. Hell of" +
            " messenger bag chillwave hashtag, distillery thundercats aesthetic roof party lo-fi sustainable" +
            " jean shorts single-origin coffee. Distillery ugh green juice, hammock marfa gastropub mlkshk" +
            " chambray vegan aesthetic beard listicle skateboard ramps literally.")
        ))
        val expected = Document(testUUID.toString, Map(
          "name" -> testWorkspace.name,
          "namespace" -> testWorkspace.namespace,
          "workspaceId" -> testWorkspace.workspaceId
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with mixed library and default attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          "library:foo"->"foo",
          "library:bar"->"bar",
          "baz"->"defaultBaz",
          "qux"->"defaultQux"
        ))
        val expected = Document(testUUID.toString, Map(
          "library:foo" -> "foo",
          "library:bar" -> "bar",
          "name" -> testWorkspace.name,
          "namespace" -> testWorkspace.namespace,
          "workspaceId" -> testWorkspace.workspaceId
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with illegally-namespaced attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          "library:foo"->"foo",
          "library:bar"->"bar",
          "baz"->"defaultBaz",
          "qux"->"defaultQux",
          "nope:foo"->"foo",
          "default:bar"->"bar"
        ))
        val expected = Document(testUUID.toString, Map(
          "library:foo" -> "foo",
          "library:bar" -> "bar",
          "name" -> testWorkspace.name,
          "namespace" -> testWorkspace.namespace,
          "workspaceId" -> testWorkspace.workspaceId
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "in its schema definition" - {
      "has valid JSON" in {
        val fileContents = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val jsonVal:Try[JsValue] = Try(fileContents.parseJson)
        assert(jsonVal.isSuccess, "Schema should be valid json")
      }
      "has valid JSON Schema" in {
        val schemaStream = new URL("http://json-schema.org/draft-04/schema").openStream()
        val schemaStr = FileUtils.readAllText(schemaStream)
        val fileContents = FileUtils.readAllTextFromResource("library/attribute-definitions.json")

        try {
          validateJsonSchema(fileContents, schemaStr)
        } finally {
          schemaStream.close()
        }
      }
    }
  }
}
