package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.{Document, LibrarySearchResponse}
import spray.json.JsObject

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

  override def bulkIndex(docs: Seq[Document]) = Unit
  override def indexDocument(doc: Document) = {
    indexDocumentInvoked = true
  }
  override def deleteDocument(id: String) = {
    deleteDocumentInvoked = true
  }

  override def findDocuments(term: String, from: Int, size: Int): LibrarySearchResponse = {
    findDocumentsInvoked = true
    LibrarySearchResponse(0, Array[JsObject]())
  }

}
