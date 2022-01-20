package org.broadinstitute.dsde.firecloud.service

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.dataaccess.{ConsentDAO, OntologyDAO, RawlsDAO, SearchDAO}
import org.broadinstitute.dsde.firecloud.model.WithAccessToken
import org.broadinstitute.dsde.firecloud.service.LibraryService.publishedFlag
import org.broadinstitute.dsde.rawls.model._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait WorkspacePublishingSupport extends LibraryServiceSupport {

  implicit protected val executionContext: ExecutionContext

  implicit val userToken: WithAccessToken

  def publishDocument(ws: WorkspaceDetails, ontologyDAO: OntologyDAO, searchDAO: SearchDAO, consentDAO: ConsentDAO)(implicit userToken: WithAccessToken): Future[Unit] = {
    indexableDocuments(Seq(ws), ontologyDAO, consentDAO) map { ws =>
      assert(ws.size == 1)
      searchDAO.indexDocument(ws.head)
    }
  }

  def removeDocument(ws: WorkspaceDetails, searchDAO: SearchDAO): Unit = {
    searchDAO.deleteDocument(ws.workspaceId)
  }

  def republishDocument(ws: WorkspaceDetails, ontologyDAO: OntologyDAO, searchDAO: SearchDAO, consentDAO: ConsentDAO)(implicit userToken: WithAccessToken): Future[Unit] = {
    if (isPublished(ws)) {
      // if already published, republish
      // we do not need to delete before republish
      publishDocument(ws, ontologyDAO, searchDAO, consentDAO)
    } else {
      Future.successful(())
    }
  }

  def isPublished(workspaceResponse: WorkspaceResponse): Boolean = {
    isPublished(workspaceResponse.workspace)
  }

  def isPublished(workspace: WorkspaceDetails): Boolean = {
    workspace.attributes.getOrElse(Map.empty).get(publishedFlag).fold(false)(_.asInstanceOf[AttributeBoolean].value)
  }

  def setWorkspacePublishedStatus(ws: WorkspaceDetails, publishArg: Boolean, rawlsDAO: RawlsDAO, ontologyDAO: OntologyDAO, searchDAO: SearchDAO, consentDAO: ConsentDAO)(implicit userToken: WithAccessToken): Future[WorkspaceDetails] = {
    rawlsDAO.updateLibraryAttributes(ws.namespace, ws.name, updatePublishAttribute(publishArg)) flatMap { workspace =>
      val docPublishFuture = if (publishArg) {
        publishDocument(workspace, ontologyDAO, searchDAO, consentDAO)
      } else {
        Future(removeDocument(workspace, searchDAO))
      }

      docPublishFuture.map(_ => workspace).recover {
        case throwable: Throwable =>
          val message = s"Unable to update this workspace, ${ws.namespace}:${ws.name}, to $publishArg in elastic search."
          logger.error(message, throwable)
          throw new FireCloudException(message, throwable)
      }
    }
  }

}
