package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.rawls.model.{Attribute, AttributeBoolean, AttributeName}
import org.scalatest.{Assertions, FreeSpec}

class WorkspaceModelSpec extends FreeSpec with Assertions {

  val wc = WorkspaceCreate("namespace", "name", Map())

  "Workspace Model" - {

    "Workspace Create" in {
      assert(!wc.isProtected.getOrElse(false))
    }

    "Workspace Clone" in {
      val clone = WorkspaceCreate.toWorkspaceClone(wc)
      assert(!clone.isProtected.getOrElse(false))
      assert(clone.attributes.nonEmpty)
      assert(clone.attributes.contains(AttributeName("library","published")))
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
