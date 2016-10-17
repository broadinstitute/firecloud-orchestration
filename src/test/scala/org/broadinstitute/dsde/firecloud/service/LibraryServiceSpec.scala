package org.broadinstitute.dsde.firecloud.service

import java.util.UUID
import org.broadinstitute.dsde.firecloud.dataaccess.RawlsDAO
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, _}
import org.broadinstitute.dsde.firecloud.model.{AttributeBoolean, AttributeName, AttributeString, UserInfo}
import org.scalatest.FreeSpec
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

import scala.concurrent.ExecutionContext

class LibraryServiceSpec extends FreeSpec with LibraryServiceSupport {
  def toName(s:String) = AttributeName.fromDelimitedName(s)

  val existingLibraryAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "library:keythree"->"valthree", "library:keyfour"->"valfour").toJson.convertTo[AttributeMap]
  val existingMixedAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour").toJson.convertTo[AttributeMap]
  val existingPublishedAttrs = Map("library:published"->"true", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour").toJson.convertTo[AttributeMap]

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
        val newAttrs = """{}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keyone")),
          RemoveAttribute(toName("library:keytwo")),
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs)
        }
      }
    }
    "when new attrs are a subset" - {
      "should calculate removals and updates" in {
        val newAttrs = """{"library:keyone":"valoneNew", "library:keytwo":"valtwoNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew")),
          AddUpdateAttribute(toName("library:keytwo"),AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs)
        }
      }
    }
    "when new attrs contain an array" - {
      "should calculate list updates" in {
        val newAttrs =
          """{"library:keyone":"valoneNew",
            | "library:keytwo":
            | {
            |   "itemsType" : "AttributeValue",
            |   "items" : ["valtwoA","valtwoB","valtwoC"]
            | }
            |}""".stripMargin.parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          RemoveAttribute(toName("library:keytwo")),
          AddUpdateAttribute(toName("library:keyone"), AttributeString("valoneNew")),
          AddListMember(toName("library:keytwo"), AttributeString("valtwoA")),
          AddListMember(toName("library:keytwo"), AttributeString("valtwoB")),
          AddListMember(toName("library:keytwo"), AttributeString("valtwoC"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs)
        }
      }
    }
    "when old attrs include non-library" - {
      "should not touch old non-library attrs" in {
        val newAttrs = """{"library:keyone":"valoneNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keytwo")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingMixedAttrs, newAttrs)
        }
      }
    }
    "when new attrs include non-library" - {
      "should not touch new non-library attrs" in {
        val newAttrs = """{"library:keyone":"valoneNew", "library:keytwo":"valtwoNew", "333":"three", "444":"four"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew")),
          AddUpdateAttribute(toName("library:keytwo"),AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs)
        }
      }
    }
    "when old attrs include published flag" - {
      "should not touch old published flag" in {
        val newAttrs = """{"library:keyone":"valoneNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keytwo")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingPublishedAttrs, newAttrs)
        }
      }
    }
    "when new attrs include published flag" - {
      "should not touch old published flag" in {
        val newAttrs = """{"library:published":"true","library:keyone":"valoneNew", "library:keytwo":"valtwoNew"}""".parseJson.convertTo[AttributeMap]
        val expected = Seq(
          RemoveAttribute(toName("library:keythree")),
          RemoveAttribute(toName("library:keyfour")),
          AddUpdateAttribute(toName("library:keyone"),AttributeString("valoneNew")),
          AddUpdateAttribute(toName("library:keytwo"),AttributeString("valtwoNew"))
        )
        assertResult(expected) {
          generateAttributeOperations(existingLibraryAttrs, newAttrs)
        }
      }
    }
    "when publishing a workspace" - {
      "should add a library:published attribute" in {
        val expected = Seq(AddUpdateAttribute(toName("library:published"),AttributeBoolean(true)))
        assertResult(expected) {
          updatePublishAttribute(true)
        }
      }
    }
    "when unpublishing a workspace" - {
      "should remove the library:published attribute" in {
        val expected = Seq(RemoveAttribute(toName("library:published")))
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
  }
}
