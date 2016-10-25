package org.broadinstitute.dsde.firecloud.service

import java.net.URL
import java.util.UUID
import org.broadinstitute.dsde.firecloud.dataaccess.{ElasticSearchDAOSupport, RawlsDAO}
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, _}
import org.broadinstitute.dsde.firecloud.model.{AttributeString, Document, RawlsWorkspace}
import org.everit.json.schema.ValidationException
import org.parboiled.common.FileUtils
import org.scalatest.FreeSpec
import spray.json._

import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

import scala.util.Try

class LibraryServiceSpec extends FreeSpec with LibraryServiceSupport  with ElasticSearchDAOSupport{
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
    attributes=Map.empty,
    bucketName="bucketName",
    accessLevels=Map.empty,
    realm=None)

  val testLibraryMetadata =
    """
      |{
      |  "description" : "some description",
      |  "userAttributeOne" : "one",
      |  "userAttributeTwo" : "two",
      |  "library:datasetName" : "name",
      |  "library:datasetDescription" : "desc",
      |  "library:datasetCustodian" : "cust",
      |  "library:datasetDepositor" : "depo",
      |  "library:datasetOwner" : "owner",
      |  "library:institute" : ["inst","it","ute"],
      |  "library:indication" : "indic",
      |  "library:numSubjects" : 123,
      |  "library:projectName" : "proj",
      |  "library:datatype" : ["data","type"],
      |  "library:dataUseRestriction" : "dur",
      |  "library:studyDesign" : "study",
      |  "library:cellType" : "cell",
      |  "library:technology" : ["is an optional","array attribute"]
      |}
    """.stripMargin

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
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with only default attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
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
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
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
          AttributeName.withDefaultNS("description")->AttributeString("Fingerstache copper mug edison bulb, actually austin mustache chartreuse bicycle rights." +
            " Plaid iceland artisan blog street art hammock, subway tile vice. Hammock put a bird on it pinterest tacos" +
            " kitsch gastropub. Chicharrones food truck edison bulb meh. Cardigan aesthetic vegan kitsch. Hell of" +
            " messenger bag chillwave hashtag, distillery thundercats aesthetic roof party lo-fi sustainable" +
            " jean shorts single-origin coffee. Distillery ugh green juice, hammock marfa gastropub mlkshk" +
            " chambray vegan aesthetic beard listicle skateboard ramps literally.")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with mixed library and default attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "with illegally-namespaced attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux"),
          AttributeName("nope","foo")->AttributeString("foo"),
          AttributeName("default","bar")->AttributeString("bar")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","bar")->AttributeString("bar"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
        ))
        assertResult(expected) {
          indexableDocument(w)
        }
      }
    }
    "in its runtime schema definition" - {
      "has valid JSON" in {
        val fileContents = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val jsonVal: Try[JsValue] = Try(fileContents.parseJson)
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
    "when validating JSON Schema" - {
      "fails on an empty JsObject" in {
        val testSchema = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        val sampleData = "{}"
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(13) {
          ex.getViolationCount
        }
      }
      "fails with one missing key" in {
        val testSchema = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields - "library:datasetName").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1) {
          ex.getViolationCount
        }
        assert(ex.getMessage.contains("library:datasetName"))
      }
      "fails with two missing keys" in {
        val testSchema = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields - "library:datasetName" - "library:datasetOwner").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(2) {
          ex.getViolationCount
        }
      }
      "fails on a string that should be a number" in {
        val testSchema = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:numSubjects", JsString("isString"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1) {
          ex.getViolationCount
        }
        assert(ex.getMessage.contains("library:numSubjects"))
      }
      "fails on a number out of bounds" in {
        val testSchema = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:numSubjects", JsNumber(-1))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1) {
          ex.getViolationCount
        }
        assert(ex.getMessage.contains("library:numSubjects"))
      }
      "fails on a string that should be an array" in {
        val testSchema = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:institute", JsString("isString"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1) {
          ex.getViolationCount
        }
        assert(ex.getMessage.contains("library:institute"))
      }
      "validates on a complete metadata packet" in {
        val testSchema = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        validateJsonSchema(testLibraryMetadata, testSchema)

      }
    }
    "when creating schema mappings" - {
      "works for string type" in {
        val label = "library:attr"
        val `type` = "string"
        val expected = Map(label -> Left(ESDetail(`type`)))
        assertResult(expected) {
          detailFromAttribute(label, AttributeDetail(`type`))
        }
      }
      "works for array type" in {
        val label = "library:attr"
        val `type` = "array"
        val subtype = "string"
        val detail = AttributeDetail(`type`, Some(AttributeDetail(subtype)))
        val expected = Map(label -> Right(ESArray(ESItem(ESDetail(subtype)))))
        assertResult(expected) {
          detailFromAttribute(label, detail)
        }
      }
      "mapping has valid json" in {
        val attrJson = FileUtils.readAllTextFromResource("test-attribute-definitions.json")
        val testJson = makeMapping(attrJson)
        val jsonVal: Try[JsValue] = Try(testJson.parseJson)
        assert(jsonVal.isSuccess, "Mapping should be valid json")
        println(testJson)
      }
    }
  }
}
