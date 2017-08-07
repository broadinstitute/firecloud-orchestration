package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.dataaccess.{OntologyDAO, RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.service.LibraryService.publishedFlag
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, Workspace, WorkspaceResponse}

import scala.concurrent.ExecutionContext

trait WorkspacePublishingSupport extends LibraryServiceSupport {

  implicit protected val executionContext: ExecutionContext

  def publishDocument(ws: Workspace, ontologyDAO: OntologyDAO, searchDAO: SearchDAO): Unit = {
    indexableDocuments(Seq(ws), ontologyDAO) map { ws =>
      assert(ws.size == 1)
      searchDAO.indexDocument(ws.head)
    }
  }

  def removeDocument(ws: Workspace, searchDAO: SearchDAO): Unit = {
    searchDAO.deleteDocument(ws.workspaceId)
  }

  def republishDocument(ws: Workspace, ontologyDAO: OntologyDAO, searchDAO: SearchDAO): Unit = {
    if (isPublished(ws)) {
      // if already published, republish
      // we do not need to delete before republish
      publishDocument(ws, ontologyDAO, searchDAO)
    }
  }

  def isPublished(workspaceResponse: WorkspaceResponse): Boolean = {
    isPublished(workspaceResponse.workspace)
  }

  def isPublished(workspace: Workspace): Boolean = {
    workspace.attributes.get(publishedFlag).fold(false)(_.asInstanceOf[AttributeBoolean].value)
  }

}
