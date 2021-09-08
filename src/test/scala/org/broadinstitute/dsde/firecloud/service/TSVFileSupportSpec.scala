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
      val resultingOps = setAttributesOnEntity("some_type", Some("idc"), MockTSVLoadFiles.entityWithAttributeStringArray.tsvData.head, Seq(("arrays", Some("foo"))), FlexibleModelSchema)
      println(resultingOps)
      resultingOps.operations.size shouldBe 3
      resultingOps.entityType shouldBe "some_type"
      resultingOps.operations.map(_.values) should contain theSameElementsAs List(AttributeBoolean(true), AttributeBoolean(false), AttributeBoolean(true))
    }

    "parse an attribute array consisting of all numbers" in {
      val resultingOps = setAttributesOnEntity("some_type", Some("idc"), MockTSVLoadFiles.entityWithAttributeNumberArray.tsvData.head, Seq(("arrays", Some("foo"))), FlexibleModelSchema)
      resultingOps.operations.size shouldBe 3
    }

    "parse an attribute array consisting of all booleans" in {
      val resultingOps = setAttributesOnEntity("some_type", Some("idc"), MockTSVLoadFiles.entityWithAttributeBooleanArray.tsvData.head, Seq(("arrays", Some("foo"))), FlexibleModelSchema)
      resultingOps.operations.size shouldBe 3
    }

    "throw an exception when parsing an attribute array consisting of mixed attribute types" in {
      intercept[FireCloudException] {
        setAttributesOnEntity("some_type", Some("idc"), MockTSVLoadFiles.entityWithAttributeMixedArray.tsvData.head, Seq(("arrays", Some("foo"))), FlexibleModelSchema)
      }

    }
  }

}
