package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.JsValue

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by davidan on 10/6/16.
  */
class MockSearchDAO extends SearchDAO {

  override def initIndex() = ()
  override def recreateIndex() = ()
  override def indexExists() = false
  override def createIndex() = ()
  override def deleteIndex() = ()

  var indexDocumentInvoked = new AtomicBoolean(false)
  var deleteDocumentInvoked = new AtomicBoolean(false)
  var findDocumentsInvoked = new AtomicBoolean(false)
  var autocompleteInvoked = new AtomicBoolean(false)
  var populateSuggestInvoked = new AtomicBoolean(false)

  override def bulkIndex(docs: Seq[Document], refresh:Boolean = false) = LibraryBulkIndexResponse(0, false, Map.empty)

  override def indexDocument(doc: Document) = {
    indexDocumentInvoked.set(true)
  }

  override def deleteDocument(id: String) = {
    deleteDocumentInvoked.set(true)
  }

  override def findDocuments(librarySearchParams: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse] = {
    findDocumentsInvoked.set(true)
    Future(LibrarySearchResponse(librarySearchParams, 0, Seq[JsValue](), Seq[LibraryAggregationResponse]()))
  }

  override def suggestionsFromAll(librarySearchParams: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse] = {
    autocompleteInvoked.set(true)
    Future(LibrarySearchResponse(librarySearchParams, 0, Seq[JsValue](), Seq[LibraryAggregationResponse]()))
  }

  override def suggestionsForFieldPopulate(field: String, text: String): Future[Seq[String]] = {
    populateSuggestInvoked.set(true)
    Future(Seq(field, text))
  }

  def reset() = {
    indexDocumentInvoked.set(false)
    deleteDocumentInvoked.set(false)
    findDocumentsInvoked.set(false)
    autocompleteInvoked.set(false)
    populateSuggestInvoked.set(false)
  }

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

}
