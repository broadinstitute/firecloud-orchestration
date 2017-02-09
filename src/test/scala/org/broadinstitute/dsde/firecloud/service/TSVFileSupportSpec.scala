package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.mock.MockTSVLoadFiles
import org.broadinstitute.dsde.rawls.model.{AttributeName, AttributeString}
import org.broadinstitute.dsde.firecloud.model.AttributeUpdateOperations.{AddUpdateAttribute, RemoveAttribute}
import org.scalatest.FreeSpec

/**
  * Created by ansingh on 11/16/16.
  */
class TSVFileSupportSpec extends FreeSpec with TSVFileSupport {


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

}
