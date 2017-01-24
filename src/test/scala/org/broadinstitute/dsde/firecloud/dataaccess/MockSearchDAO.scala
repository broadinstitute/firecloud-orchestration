package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model._
import spray.json.JsValue

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by davidan on 10/6/16.
  */
class MockSearchDAO extends SearchDAO {

  override def initIndex = Unit
  override def recreateIndex = Unit
  override def indexExists = false
  override def createIndex = Unit
  override def deleteIndex = Unit

  var indexDocumentInvoked = false
  var deleteDocumentInvoked = false
  var findDocumentsInvoked = false
  var autocompleteInvoked = false

  override def bulkIndex(docs: Seq[Document]) = Unit

  override def indexDocument(doc: Document) = {
    indexDocumentInvoked = true
  }

  override def deleteDocument(id: String) = {
    deleteDocumentInvoked = true
  }

  override def findDocuments(librarySearchParams: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {
    findDocumentsInvoked = true
    Future(LibrarySearchResponse(librarySearchParams, 0, Seq[JsValue](), Seq[LibraryAggregationResponse]()))
  }

  override def suggest(librarySearchParams: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {
    autocompleteInvoked = true
    Future(LibrarySearchResponse(librarySearchParams, 0, Seq[JsValue](), Seq[LibraryAggregationResponse]()))
  }

}
