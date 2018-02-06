package org.broadinstitute.dsde.firecloud.dataaccess

import java.util.concurrent.atomic.AtomicBoolean

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import spray.json.JsValue

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by davidan on 10/6/16.
  */
class MockSearchDAO extends SearchDAO {

  override def initIndex: Unit = Unit
  override def recreateIndex: Unit = Unit
  override def indexExists = false
  override def createIndex: Unit = Unit
  override def deleteIndex: Unit = Unit

  private val indexDocumentInvokedAtomic = new AtomicBoolean(false)
  private val deleteDocumentInvokedAtomic = new AtomicBoolean(false)
  private val findDocumentsInvokedAtomic = new AtomicBoolean(false)

  lazy val indexDocumentInvoked: Boolean = indexDocumentInvokedAtomic.get()
  lazy val deleteDocumentInvoked: Boolean = deleteDocumentInvokedAtomic.get()
  lazy val findDocumentsInvoked: Boolean = findDocumentsInvokedAtomic.get()
  var autocompleteInvoked = false
  var populateSuggestInvoked = false

  override def bulkIndex(docs: Seq[Document], refresh:Boolean = false) = LibraryBulkIndexResponse(0, false, Map.empty)

  override def indexDocument(doc: Document): Unit = {
    indexDocumentInvokedAtomic.set(true)
  }

  override def deleteDocument(id: String): Unit = {
    deleteDocumentInvokedAtomic.set(true)
  }

  override def findDocuments(librarySearchParams: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {
    findDocumentsInvokedAtomic.set(true)
    Future(LibrarySearchResponse(librarySearchParams, 0, Seq[JsValue](), Seq[LibraryAggregationResponse]()))
  }

  override def suggestionsFromAll(librarySearchParams: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {
    autocompleteInvoked = true
    Future(LibrarySearchResponse(librarySearchParams, 0, Seq[JsValue](), Seq[LibraryAggregationResponse]()))
  }

  override def suggestionsForFieldPopulate(field: String, text: String): Future[Seq[String]] = {
    populateSuggestInvoked = true
    Future(Seq(field, text))
  }

  def reset: Unit = {
    indexDocumentInvokedAtomic.set(false)
    deleteDocumentInvokedAtomic.set(false)
    findDocumentsInvokedAtomic.set(false)
    autocompleteInvoked = false
    populateSuggestInvoked = false
  }

  def setDeleteStatus(deleted: Boolean): Unit = {
    deleteDocumentInvokedAtomic.set(deleted)
  }

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

}
