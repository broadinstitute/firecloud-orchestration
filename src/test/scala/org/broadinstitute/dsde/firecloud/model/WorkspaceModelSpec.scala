package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.rawls.model.{Attribute, AttributeBoolean, AttributeName}
import org.scalatest.{Assertions, FreeSpec}

class WorkspaceModelSpec extends FreeSpec with Assertions {

  val wc = WorkspaceCreate("namespace", "name", Map())

  "Workspace Model" - {

    "Workspace Create" in {
      assert(!wc.isProtected.getOrElse(false))
    }

    "Workspace Clone is unpublished" in {
      val clone = WorkspaceCreate.toWorkspaceClone(wc)
      assert(!clone.isProtected.getOrElse(false))
      assert(clone.attributes.nonEmpty)
      assert(clone.attributes.contains(AttributeName("library","published")))
      val published: Option[Attribute] = clone.attributes.get(key = AttributeName("library", "published"))
      assert(published.getOrElse(None).isInstanceOf[AttributeBoolean])
      assert(!published.getOrElse(None).asInstanceOf[AttributeBoolean].value)
    }

    "Workspace with published attribute is unpublished when cloned" in {
      val publishedWC = WorkspaceCreate("namespace", "name", Map(AttributeName("library","published") -> AttributeBoolean(true)))
      val clone = WorkspaceCreate.toWorkspaceClone(publishedWC)
      val published: Option[Attribute] = clone.attributes.get(key = AttributeName("library", "published"))
      assert(published.getOrElse(None).isInstanceOf[AttributeBoolean])
      assert(!published.getOrElse(None).asInstanceOf[AttributeBoolean].value)
    }

    "Workspace Request" in {
      val request = WorkspaceCreate.toWorkspaceRequest(wc)
      assert(request.realm.isEmpty)
    }

  }

}
