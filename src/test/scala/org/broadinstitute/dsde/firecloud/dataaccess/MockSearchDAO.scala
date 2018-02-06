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
  private val autocompleteInvokedAtomic = new AtomicBoolean(false)
  private val populateSuggestInvokedAtomic = new AtomicBoolean(false)

  def indexDocumentInvoked: Boolean = indexDocumentInvokedAtomic.get()
  def deleteDocumentInvoked: Boolean = deleteDocumentInvokedAtomic.get()
  def findDocumentsInvoked: Boolean = findDocumentsInvokedAtomic.get()
  def autocompleteInvoked: Boolean = autocompleteInvokedAtomic.get()
  def populateSuggestInvoked: Boolean = populateSuggestInvokedAtomic.get()

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
    autocompleteInvokedAtomic.set(true)
    Future(LibrarySearchResponse(librarySearchParams, 0, Seq[JsValue](), Seq[LibraryAggregationResponse]()))
  }

  override def suggestionsForFieldPopulate(field: String, text: String): Future[Seq[String]] = {
    populateSuggestInvokedAtomic.set(true)
    Future(Seq(field, text))
  }

  def reset: Unit = {
    indexDocumentInvokedAtomic.set(false)
    deleteDocumentInvokedAtomic.set(false)
    findDocumentsInvokedAtomic.set(false)
    autocompleteInvokedAtomic.set(false)
    populateSuggestInvokedAtomic.set(false)
  }

  def setDeleteStatus(deleted: Boolean): Unit = {
    deleteDocumentInvokedAtomic.set(deleted)
  }

  def status: Future[SubsystemStatus] = Future(SubsystemStatus(ok = true, None))

}
