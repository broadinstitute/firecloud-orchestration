package org.broadinstitute.dsde.firecloud.service

import java.net.URL
import java.util.UUID

import org.broadinstitute.dsde.firecloud.dataaccess.ElasticSearchDAOSupport
import org.broadinstitute.dsde.rawls.model._
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddListMember, AddUpdateAttribute, _}
import org.broadinstitute.dsde.firecloud.model._
import org.everit.json.schema.ValidationException
import org.parboiled.common.FileUtils
import org.scalatest.FreeSpecLike
import spray.json.{JsObject, _}
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.Ontology.{TermParent, TermResource}
import org.joda.time.DateTime

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

class LibraryServiceSpec extends BaseServiceSpec with FreeSpecLike with LibraryServiceSupport with AttributeSupport with ElasticSearchDAOSupport {
  def toName(s:String) = AttributeName.fromDelimitedName(s)

  val libraryAttributePredicate = (k: AttributeName) => k.namespace == AttributeName.libraryNamespace && k.name != LibraryService.publishedFlag.name

  val existingLibraryAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "library:keythree"->"valthree", "library:keyfour"->"valfour").toJson.convertTo[AttributeMap]
  val existingMixedAttrs = Map("library:keyone"->"valone", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour").toJson.convertTo[AttributeMap]
  val existingPublishedAttrs = Map("library:published"->"true", "library:keytwo"->"valtwo", "keythree"->"valthree", "keyfour"->"valfour").toJson.convertTo[AttributeMap]

  val testUUID = UUID.randomUUID()

  val testWorkspace = new Workspace(workspaceId=testUUID.toString,
    namespace="testWorkspaceNamespace",
    name="testWorkspaceName",
    realm=None,
    isLocked=false,
    createdBy="createdBy",
    createdDate=DateTime.now(),
    lastModified=DateTime.now(),
    attributes=Map.empty,
    bucketName="bucketName",
    accessLevels=Map.empty,
    realmACLs=Map())

  val testLibraryMetadata =
    """
      |{
      |  "description" : "some description",
      |  "userAttributeOne" : "one",
      |  "userAttributeTwo" : "two",
      |  "library:datasetName" : "name",
      |  "library:datasetVersion" : "v1.0",
      |  "library:datasetDescription" : "desc",
      |  "library:datasetCustodian" : "cust",
      |  "library:datasetDepositor" : "depo",
      |  "library:contactEmail" : "name@example.com",
      |  "library:datasetOwner" : "owner",
      |  "library:institute" : ["inst","it","ute"],
      |  "library:indication" : "indic",
      |  "library:numSubjects" : 123,
      |  "library:projectName" : "proj",
      |  "library:datatype" : ["data","type"],
      |  "library:dataCategory" : ["data","category"],
      |  "library:dataUseRestriction" : "dur",
      |  "library:studyDesign" : "study",
      |  "library:cellType" : "cell",
      |  "library:requiresExternalApproval" : false,
      |  "library:technology" : ["is an optional","array attribute"],
      |  "library:useLimitationOption" : "orsp",
      |  "library:orsp" : "some orsp",
      |  "_discoverableByGroups" : ["Group1","Group2"]
      |}
    """.stripMargin
  val testLibraryMetadataJsObject = testLibraryMetadata.parseJson.asJsObject

  val DULAdditionalJsObject =
    """
      |{
      |  "library:GRU"  : true,
      |  "library:HMB"  : false,
      |  "library:NCU"  : false,
      |  "library:NPU"  : false,
      |  "library:NDMS" : true,
      |  "library:NAGR" : "Unspecified",
      |  "library:NCTRL": true,
      |  "library:RS-G" : "Female",
      |  "library:RS-PD": false
      |}
    """.stripMargin.parseJson.asJsObject
  val DULfields = (testLibraryMetadataJsObject.fields-"library:orsp") ++ DULAdditionalJsObject.fields
  val testLibraryDULMetadata = testLibraryMetadataJsObject.copy(DULfields).compactPrint

  val dur = Duration(2, MINUTES)

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
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
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
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
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
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
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
          generateAttributeOperations(existingMixedAttrs, newAttrs, libraryAttributePredicate)
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
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
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
          generateAttributeOperations(existingPublishedAttrs, newAttrs, libraryAttributePredicate)
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
          generateAttributeOperations(existingLibraryAttrs, newAttrs, libraryAttributePredicate)
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
          Await.result(indexableDocument(w, duosDao), dur)
        }
      }
    }
    "with only default attributes in workspace" - {
      "should generate indexable document" in {
        val w = testWorkspace.copy(attributes = Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName("library","discoverableByGroups")->AttributeValueList(Seq(AttributeString("Group1"))),
          AttributeName.withDefaultNS("baz")->AttributeString("defaultBaz"),
          AttributeName.withDefaultNS("qux")->AttributeString("defaultQux")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName("library","foo")->AttributeString("foo"),
          AttributeName.withDefaultNS("_discoverableByGroups") -> AttributeValueList(Seq(AttributeString("Group1"))),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
        ))
        assertResult(expected) {
          Await.result(indexableDocument(w, duosDao), dur)
        }
      }
    }
    "with discoverableByGroup attribute in workspace" - {
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
          Await.result(indexableDocument(w, duosDao), dur)
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
          Await.result(indexableDocument(w, duosDao), dur)
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
          Await.result(indexableDocument(w, duosDao), dur)
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
          Await.result(indexableDocument(w, duosDao), dur)
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
          Await.result(indexableDocument(w, duosDao), dur)
        }
      }
    }
    "with diseaseOntologyID attribute" - {
      "should generate indexable document with parent info when DOID valid" in {
        val w = testWorkspace.copy(attributes = Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("DOID_9220")
        ))
        val parentData = duosDao.data("DOID_9220").head.parents.get.map(_.toESTermParent)
        val expected = Document(testUUID.toString, Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("DOID_9220"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId),
          AttributeName.withDefaultNS("parents") -> AttributeValueRawJson(parentData.toJson.compactPrint)
        ))
        assertResult(expected) {
          Await.result(indexableDocument(w, duosDao), dur)
        }
      }
      "should generate indexable document with no parent info when DOID has no parents" in {
        val w = testWorkspace.copy(attributes = Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("DOID_4")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("DOID_4"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
        ))
        assertResult(expected) {
          Await.result(indexableDocument(w, duosDao), dur)
        }
      }
      "should generate indexable document with no parent info when DOID not valid" in {
        val w = testWorkspace.copy(attributes = Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("DOID_99999")
        ))
        val expected = Document(testUUID.toString, Map(
          AttributeName.withLibraryNS("diseaseOntologyID") -> AttributeString("DOID_99999"),
          AttributeName.withDefaultNS("name") -> AttributeString(testWorkspace.name),
          AttributeName.withDefaultNS("namespace") -> AttributeString(testWorkspace.namespace),
          AttributeName.withDefaultNS("workspaceId") -> AttributeString(testWorkspace.workspaceId)
        ))
        assertResult(expected) {
          Await.result(indexableDocument(w, duosDao), dur)
        }
      }
    }
    "in its runtime schema definition" - {
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
    "when validating JSON Schema" - {
      "fails on an empty JsObject" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val sampleData = "{}"
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(30){ex.getViolationCount}
      }
      "fails with one missing key" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:datasetName").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.last.getMessage.contains("library:datasetName"))
      }
      "fails with two missing keys" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:datasetName"-"library:datasetOwner").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(2){ex.getViolationCount}
      }
      "fails on a string that should be a number" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:numSubjects", JsString("isString"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.last.getMessage.contains("library:numSubjects"))
      }
      "fails on a number out of bounds" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:numSubjects", JsNumber(-1))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.last.getMessage.contains("library:numSubjects"))
      }
      "fails on a value outside its enum" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:coverage", JsString("foobar"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.last.getMessage.contains("library:coverage: foobar is not a valid enum value"))
      }
      "fails on a string that should be an array" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:institute", JsString("isString"))).compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        assertResult(1){ex.getViolationCount}
        assert(ex.getCausingExceptions.last.getMessage.contains("library:institute"))
      }
      "fails with missing ORSP key" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:orsp").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        // require violations inside the oneOf schemas are extra-nested, and can include errors from all
        // subschemas in the oneOf list. Not worth testing in detail.
      }
      "validates on a complete metadata packet with ORSP key" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        validateJsonSchema(testLibraryMetadata, testSchema)
      }
      "fails with one missing key from the DUL set" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:NPU").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        // require violations inside the oneOf schemas are extra-nested, and can include errors from all
        // subschemas in the oneOf list. Not worth testing in detail.
      }
      "fails on a metadata packet with all DUL keys but the wrong option chosen" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val ex = intercept[ValidationException] {
          validateJsonSchema(testLibraryDULMetadata, testSchema)
        }
      }
      "validates on a complete metadata packet with all DUL keys and the correct option chosen" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields.updated("library:useLimitationOption", JsString("questionnaire"))).compactPrint
        validateJsonSchema(sampleData, testSchema)
      }

      "has error messages for top-level missing keys" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:datasetName"-"library:datasetOwner").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        val errorMessages = getSchemaValidationMessages(ex)
        assert( errorMessages.contains("#: required key [library:datasetName] not found"),
          "does not have library:datasetName in error messages" )
        assert( errorMessages.contains("#: required key [library:datasetOwner] not found"),
          "does not have library:datasetOwner in error messages" )
      }
      "has error message for missing key from the DUR set" in {
        val testSchema = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val defaultData = testLibraryDULMetadata.parseJson.asJsObject
        val sampleData = defaultData.copy(defaultData.fields-"library:NPU").compactPrint
        val ex = intercept[ValidationException] {
          validateJsonSchema(sampleData, testSchema)
        }
        val errorMessages = getSchemaValidationMessages(ex)
        assert( errorMessages.contains("#: required key [library:NPU] not found"),
          "does not have library:NPU in error messages" )
      }
    }
    "when creating schema mappings" - {
      "works for string type" in {
        val label = "library:attr"
        val `type` = "string"
        val expected = label -> ESType(`type`, false, true, false)
        assertResult(expected) {
          createType(label, AttributeDetail(`type`))
        }
      }
      "works for aggregatable string type" in {
        val label = "library:attr"
        val `type` = "string"
        val aggregateObject = JsObject("renderHint"->JsString("text"))
        val expected = label -> ESType(`type`, false, true, true)
        assertResult(expected) {
          createType(label, AttributeDetail(`type`, None, Some(aggregateObject)))
        }
        val result = createType(label, AttributeDetail(`type`, None, Some(aggregateObject)))
      }
      "works for array type" in {
        val label = "library:attr"
        val `type` = "array"
        val subtype = "string"
        val detail = AttributeDetail(`type`, Some(AttributeDetail(subtype)))
        val expected = label -> ESType(subtype, false, true, false)
        assertResult(expected) {
          createType(label, detail)
        }
      }
      "works for aggregatable array type" in {
        val label = "library:attr"
        val `type` = "array"
        val subtype = "string"
        val aggregateObject = JsObject("renderHint"->JsString("text"))
        val detail = AttributeDetail(`type`, Some(AttributeDetail(subtype)), Some(aggregateObject))
        val expected = label -> ESType(subtype, false, true, true)
        assertResult(expected) {
          createType(label, detail)
        }
      }
      "works for populate suggest array type" in {
        val label = "library:datatype"
        val `type` = "string"
        val detail = AttributeDetail(`type`, None, None, None, Option("populate"))
        val expected = label -> ESType(`type`, true, true, false)
        assertResult(expected) {
          createType(label, detail)
        }
      }
      "mapping has valid json" in {
        val attrJson = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val testJson = makeMapping(attrJson)
        val jsonVal: Try[JsValue] = Try(testJson.parseJson)
        assert(jsonVal.isSuccess, "Mapping should be valid json")
      }
      "non-indexable type is not in mapping" in {
        val attrJson = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val testJson = makeMapping(attrJson)
        val label = "library:lmsvn"
        assert(!testJson.contains(label))
      }
      "discoverableByGroups is in mapping" in {
        val attrJson = FileUtils.readAllTextFromResource("library/attribute-definitions.json")
        val testJson = makeMapping(attrJson)
        assert(testJson.contains(ElasticSearch.fieldDiscoverableByGroups))
      }
    }
    "when finding documents" - {
      val params = LibrarySearchParams(Some("test"), Map(), Map())
      "don't add workspaceAccess to document if can't find workspace id for a document" in {
        val doc = LibrarySearchResponse(params, 1, Seq(testLibraryMetadataJsObject: JsValue), Seq())
        assertResult(doc) {
          updateAccess(doc, Seq())
        }
      }
      "set workspaceAccess to No Access if workspace is not returned from workspace list" in {
        val result = testLibraryMetadataJsObject.copy(testLibraryMetadataJsObject.fields.updated("workspaceId", JsString("no.access.to.workspace.id")))
        val expectedResult: JsValue = result.copy(result.fields.updated("workspaceAccess", JsString(WorkspaceAccessLevels.NoAccess.toString)))
        val doc = LibrarySearchResponse(params, 1, Seq(result: JsValue), Seq())
        assertResult(doc.copy(results=Seq(expectedResult))) {
          updateAccess(doc, Seq())
        }
      }
      "set workspaceAccess to workspace access level if workspace is returned from workspace list" in {
        val workspaceList = Seq(WorkspaceListResponse(WorkspaceAccessLevels.Owner, testWorkspace, WorkspaceSubmissionStats(None, None, 0), Seq()))
        val result = testLibraryMetadataJsObject.copy(testLibraryMetadataJsObject.fields.updated("workspaceId", JsString(testUUID.toString)))
        val expectedResult: JsValue = result.copy(result.fields.updated("workspaceAccess", JsString(WorkspaceAccessLevels.Owner.toString)))
        val doc = LibrarySearchResponse(params, 1, Seq(result: JsValue), Seq())
        assertResult(doc.copy(results=Seq(expectedResult))) {
          updateAccess(doc, workspaceList)
        }
      }
    }
  }
}
