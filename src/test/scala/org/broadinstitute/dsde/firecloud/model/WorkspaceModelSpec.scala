package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeName}
import org.scalatest.{Assertions, FreeSpec}

class WorkspaceModelSpec extends FreeSpec with Assertions {

  val wc = WorkspaceCreate("namespace", "name", Set.empty, Map())

  "Workspace Model" - {

    "Workspace Create is not protected" in {
      assert(wc.authorizationDomain.isEmpty)
    }

    "Workspace Clone is unpublished" in {
      assert(!isPublished(WorkspaceCreate.toWorkspaceClone(wc)))
    }

    "Workspace with published attribute is unpublished when cloned" in {
      val publishedWC = WorkspaceCreate("namespace", "name", Set.empty, Map(AttributeName("library","published") -> AttributeBoolean(true)))
      assert(!isPublished(WorkspaceCreate.toWorkspaceClone(publishedWC)))
    }

    "Workspace Request has no authorization domain" in {
      val request = WorkspaceCreate.toWorkspaceRequest(wc)
      assert(request.authorizationDomain.isEmpty)
    }

  }

  private def isPublished(wc: WorkspaceCreate): Boolean = {
    wc.attributes.
      getOrElse(AttributeName("library","published"), AttributeBoolean(false)).
      asInstanceOf[AttributeBoolean].
      value
  }

}
