package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.dataaccess.{OntologyDAO, RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.LibraryService.publishedFlag
import org.broadinstitute.dsde.rawls.model.{AttributeBoolean, ErrorReport, Workspace, WorkspaceResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

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

  def setWorkspacePublishedStatus(ws: Workspace, publishArg: Boolean, rawlsDAO: RawlsDAO, ontologyDAO: OntologyDAO, searchDAO: SearchDAO)(implicit userToken: WithAccessToken): Future[Workspace] = {
    rawlsDAO.updateLibraryAttributes(ws.namespace, ws.name, updatePublishAttribute(publishArg)) map { workspace =>
      val docPublishResult = Try {
        if (publishArg)
          publishDocument(workspace, ontologyDAO, searchDAO)
        else
          removeDocument(workspace, searchDAO)
      }.isSuccess
      if (docPublishResult)
        workspace
      else {
        val message = s"Unable to update this workspace, ${ws.namespace}:${ws.name}, to $publishArg in elastic search."
        logger.error(message)
        throw new FireCloudException(s"Unable to update this workspace, ${ws.namespace}:${ws.name}, to $publishArg in elastic search.")
      }
    }
  }

}
