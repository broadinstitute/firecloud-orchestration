package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, AttributeName}
import org.scalatest.{Assertions, FreeSpec}

class WorkspaceModelSpec extends FreeSpec with Assertions {

  val wc = WorkspaceCreate("namespace", "name", None, Map())

  "Workspace Model" - {

    "Workspace Create is not protected" in {
      assert(!wc.authorizationDomain.isDefined)
    }

    "Workspace Clone is unpublished" in {
      assert(!isPublished(WorkspaceCreate.toWorkspaceClone(wc)))
    }

    "Workspace with published attribute is unpublished when cloned" in {
      val publishedWC = WorkspaceCreate("namespace", "name", None, Map(AttributeName("library","published") -> AttributeBoolean(true)))
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
