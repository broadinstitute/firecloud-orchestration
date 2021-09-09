package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.mock.MockTSVLoadFiles
import org.broadinstitute.dsde.firecloud.model.FlexibleModelSchema
import org.broadinstitute.dsde.firecloud.utils.TSVLoadFile
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeName, AttributeNumber, AttributeString, AttributeValue}
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
                                exemplarValue: AttributeValue,
                                expectedSize: Int = 4,
                                colname: String = "arrays",
                                entityType: String = "some_type")

    val testCases = List(
      TsvArrayTestCase(MockTSVLoadFiles.entityWithAttributeStringArray, "all strings", AttributeString("")),
      TsvArrayTestCase(MockTSVLoadFiles.entityWithAttributeNumberArray, "all numbers", AttributeNumber(0)),
      TsvArrayTestCase(MockTSVLoadFiles.entityWithAttributeBooleanArray, "all booleans", AttributeBoolean(true))
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
      intercept[FireCloudException] {
        setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithAttributeMixedArray.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)
      }
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
  }

}
