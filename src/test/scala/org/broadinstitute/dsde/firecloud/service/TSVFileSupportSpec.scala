package org.broadinstitute.dsde.firecloud.service

import akka.http.scaladsl.model.StatusCodes.BadRequest
import org.broadinstitute.dsde.firecloud.EntityService.colNamesToAttributeNames
import org.broadinstitute.dsde.firecloud.FireCloudExceptionWithErrorReport
import org.broadinstitute.dsde.firecloud.mock.MockTSVLoadFiles
import org.broadinstitute.dsde.firecloud.model.{EntityUpdateDefinition, FlexibleModelSchema}
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeEntityReference, AttributeListElementable, AttributeName, AttributeNumber, AttributeString, AttributeValueRawJson}
import org.broadinstitute.dsde.rawls.model.AttributeUpdateOperations.{AddUpdateAttribute, RemoveAttribute}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

/**
  * Created by ansingh on 11/16/16.
  */
class TSVFileSupportSpec extends AnyFreeSpec with TSVFileSupport {


  "getWorkspaceAttributeCalls" - {
    "get AttributeUpdates for a valid Workspace Attribute TSV file" in {
      val attributes = getWorkspaceAttributeCalls(MockTSVLoadFiles.validWorkspaceAttributes)
      assertResult(attributes) {
        List(AddUpdateAttribute(AttributeName("default", "a1"), AttributeString("v1")),
          AddUpdateAttribute(AttributeName("default", "a2"), AttributeString("2")),
          AddUpdateAttribute(AttributeName("default", "a3"), AttributeString("[1,2,3]")))
      }
    }

    "get AttributeUpdates for a TSV file with 1 attribute" in {
      val attributes = getWorkspaceAttributeCalls(MockTSVLoadFiles.validOneWorkspaceAttribute)
      assertResult(attributes) {
        List(AddUpdateAttribute(AttributeName("default", "a1"), AttributeString("v1")))
      }
    }

    "get AttributeUpdates for a TSV file with empty attribute value" in {
      val attributes = getWorkspaceAttributeCalls(MockTSVLoadFiles.validEmptyStrWSAttribute)
      assertResult(attributes) {
        List(AddUpdateAttribute(AttributeName("default", "a1"), AttributeString("")))
      }
    }

    "get AttributeUpdates for a TSV file with remove attribute" in {
      val attributes = getWorkspaceAttributeCalls(MockTSVLoadFiles.validRemoveWSAttribute)
      assertResult(attributes) {
        List(RemoveAttribute(AttributeName("default", "a1")))
      }
    }

    "get AttributeUpdates for a TSV file with add and remove attributes" in {
      val attributes = getWorkspaceAttributeCalls(MockTSVLoadFiles.validRemoveAddAttribute)
      assertResult(attributes) {
        List(RemoveAttribute(AttributeName("default", "a1")),
          AddUpdateAttribute(AttributeName("default", "a2"), AttributeString("v2")))
      }
    }
  }

  "setAttributesOnEntity" - {

    case class TsvArrayTestCase(loadFile: TSVLoadFile,
                                testHint: String,
                                exemplarValue: AttributeListElementable,
                                expectedSize: Int = 4,
                                colname: String = "arrays",
                                entityType: String = "some_type")

    val testCases = List(
      TsvArrayTestCase(MockTSVLoadFiles.entityWithAttributeStringArray, "all strings", AttributeString("")),
      TsvArrayTestCase(MockTSVLoadFiles.entityWithAttributeNumberArray, "all numbers", AttributeNumber(0)),
      TsvArrayTestCase(MockTSVLoadFiles.entityWithAttributeBooleanArray, "all booleans", AttributeBoolean(true)),
      TsvArrayTestCase(MockTSVLoadFiles.entityWithAttributeEntityReferenceArray,
        "all entity references", AttributeEntityReference("entityType", "entityName"))
    )

    testCases foreach { testCase =>
      s"parse an attribute array consisting of ${testCase.testHint}" in {
        val resultingOps = setAttributesOnEntity(testCase.entityType, None, testCase.loadFile.tsvData.head, Seq((testCase.colname, None)), FlexibleModelSchema)
        resultingOps.operations.size shouldBe testCase.expectedSize //1 to remove any existing list, 3 to add the list elements
        resultingOps.entityType shouldBe testCase.entityType

        // firstOp should be the RemoveAttribute
        val firstOp = resultingOps.operations.head
        firstOp.keySet should contain theSameElementsAs List("op", "attributeName")
        firstOp("op") shouldBe AttributeString("RemoveAttribute")
        firstOp("attributeName") shouldBe AttributeString(testCase.colname)

        val expectedClass = testCase.exemplarValue.getClass

        // remaining ops should be the AddListMembers
        val tailOps = resultingOps.operations.tail
        tailOps.foreach { op =>
          op.keySet should contain theSameElementsAs List("attributeListName", "newMember", "op")
          op("op") shouldBe AttributeString("AddListMember")
          op("attributeListName") shouldBe AttributeString(testCase.colname)
          val element = op("newMember")
          element.getClass shouldBe(expectedClass)
        }
      }
    }

    "throw an exception when parsing an attribute array consisting of mixed attribute types" in {
      val caught = intercept[FireCloudExceptionWithErrorReport] {
        setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithAttributeMixedArray.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)
      }
      caught.errorReport.statusCode should contain (BadRequest)
      caught.errorReport.message shouldBe "Mixed-type entity attribute lists are not supported."
    }

    "throw an exception when parsing an attribute array of objects" in {
      val caught = intercept[FireCloudExceptionWithErrorReport] {
        setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithAttributeArrayOfObjects.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)
      }
      caught.errorReport.statusCode should contain (BadRequest)
      caught.errorReport.message shouldBe UNSUPPORTED_ARRAY_TYPE_ERROR_MSG
    }

    "parse an attribute empty array" in {
      val resultingOps = setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithEmptyAttributeArray.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)

      resultingOps.operations.size shouldBe 2 //1 to remove any existing attribute with this name, 1 to create the empty attr value list

      // firstOp should be the RemoveAttribute
      val firstOp = resultingOps.operations.head
      firstOp.keySet should contain theSameElementsAs List("op", "attributeName")
      firstOp("op") shouldBe AttributeString("RemoveAttribute")
      firstOp("attributeName") shouldBe AttributeString("arrays")

      // second and final op should be the CreateAttributeValueList
      val lastOp = resultingOps.operations.last
      lastOp.keySet should contain theSameElementsAs List("op", "attributeName")
      lastOp("op") shouldBe AttributeString("CreateAttributeValueList")
      lastOp("attributeName") shouldBe AttributeString("arrays")
    }

    "parse an attribute array-of-arrays" in {
      val resultingOps = setAttributesOnEntity("array", None, MockTSVLoadFiles.entityWithNestedArrays.tsvData.head, Seq(("array", None)), FlexibleModelSchema)

      // 1 to remove any existing attribute with this name, 3 to add the AttributeValueRawJsons
      resultingOps.operations.size shouldBe 4

      val col = AttributeString("array")

      val expectedOps = Seq(
        Map("op" -> AttributeString("RemoveAttribute"), "attributeName" -> col),
        Map("op" -> AttributeString("AddListMember"), "attributeListName" -> col,
          "newMember" -> AttributeValueRawJson("""["one","two"]""")),
        Map("op" -> AttributeString("AddListMember"), "attributeListName" -> col,
          "newMember" -> AttributeValueRawJson("""["three","four"]""")),
        Map("op" -> AttributeString("AddListMember"), "attributeListName" -> col,
          "newMember" -> AttributeValueRawJson("""["five","six"]"""))
      )

      val expected = EntityUpdateDefinition("bla", "array", expectedOps)

      resultingOps shouldBe expected
    }

    "remove attribute values when deleteEmptyValues is set to true" in {
      val colInfo = colNamesToAttributeNames( MockTSVLoadFiles.validWithBlanks.headers, Map.empty)
      val resultingOps = setAttributesOnEntity("some_type", None, MockTSVLoadFiles.validWithBlanks.tsvData.head, colInfo, FlexibleModelSchema, true)

      resultingOps.operations.size shouldBe 2

      //TSV is 1 entity with 2 attributes, one of which is blank. deleteEmptyValues is set to true
      //We should see a RemoveAttribute op for the blank and an AddUpdateAttribute op for the non-null value
      val firstOp = resultingOps.operations.head
      firstOp.keySet should contain theSameElementsAs List("op", "attributeName")
      firstOp("op") shouldBe AttributeString("RemoveAttribute")
      firstOp("attributeName") shouldBe AttributeString("bar")

      val lastOp = resultingOps.operations.last
      lastOp.keySet should contain theSameElementsAs List("op", "attributeName", "addUpdateAttribute")
      lastOp("op") shouldBe AttributeString("AddUpdateAttribute")
      lastOp("attributeName") shouldBe AttributeString("baz")
    }

    "not remove attribute values when deleteEmptyValues is set to false" in {
      val colInfo = colNamesToAttributeNames( MockTSVLoadFiles.validWithBlanks.headers, Map.empty)
      val resultingOps = setAttributesOnEntity("some_type", None, MockTSVLoadFiles.validWithBlanks.tsvData.head, colInfo, FlexibleModelSchema, false)

      resultingOps.operations.size shouldBe 1

      //TSV is 1 entity with 2 attributes, one of which is blank. deleteEmptyValues is set to false
      //We should only see an AddUpdateAttribute op for the non-null value
      val firstOp = resultingOps.operations.head
      firstOp.keySet should contain theSameElementsAs List("op", "attributeName", "addUpdateAttribute")
      firstOp("op") shouldBe AttributeString("AddUpdateAttribute")
      firstOp("attributeName") shouldBe AttributeString("baz")

    }
  }

}
