package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.mock.MockTSVLoadFiles
import org.broadinstitute.dsde.firecloud.model.FlexibleModelSchema
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeName, AttributeString}
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
    "parse an attribute array consisting of all strings" in {
      val resultingOps = setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithAttributeStringArray.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)
      resultingOps.operations.size shouldBe 4 //1 to remove any existing list, 3 to add the list elements
      resultingOps.entityType shouldBe "some_type"

      val resultingOpNames = resultingOps.operations.map(ops => ops("op"))

      //assert that these operations always remove the existing attribute by this name
      resultingOpNames.head shouldBe AttributeString("RemoveAttribute")

      //assert that all of the remaining operations are adding a list member
      resultingOpNames.tail.map { op =>
        op shouldBe AttributeString("AddListMember")
      }
    }

    "parse an attribute array consisting of all numbers" in {
      val resultingOps = setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithAttributeNumberArray.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)
      resultingOps.operations.size shouldBe 4 //1 to remove any existing list, 3 to add the list elements
      resultingOps.entityType shouldBe "some_type"

      val resultingOpNames = resultingOps.operations.map(ops => ops("op"))

      //assert that these operations always remove the existing attribute by this name
      resultingOpNames.head shouldBe AttributeString("RemoveAttribute")

      //assert that all of the remaining operations are adding a list member
      resultingOpNames.tail.map { op =>
        op shouldBe AttributeString("AddListMember")
      }
    }

    "parse an attribute array consisting of all booleans" in {
      val resultingOps = setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithAttributeBooleanArray.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)
      resultingOps.operations.size shouldBe 4 //1 to remove any existing list, 3 to add the list elements
      resultingOps.entityType shouldBe "some_type"

      val resultingOpNames = resultingOps.operations.map(ops => ops("op"))

      //assert that these operations always remove the existing attribute by this name
      resultingOpNames.head shouldBe AttributeString("RemoveAttribute")

      //assert that all of the remaining operations are adding a list member
      resultingOpNames.tail.map { op =>
        op shouldBe AttributeString("AddListMember")
      }
    }

    "throw an exception when parsing an attribute array consisting of mixed attribute types" in {
      intercept[FireCloudException] {
        setAttributesOnEntity("some_type", None, MockTSVLoadFiles.entityWithAttributeMixedArray.tsvData.head, Seq(("arrays", None)), FlexibleModelSchema)
      }
    }
  }

}
