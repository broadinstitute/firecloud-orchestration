package org.broadinstitute.dsde.firecloud.dataaccess

import spray.json.JsObject

/**
  * Created by davidan on 10/6/16.
  */
class MockSearchDAO extends SearchDAO {

  override def initIndex = Unit
  override def recreateIndex = Unit
  override def indexExists = Unit
  override def createIndex = Unit
  override def deleteIndex = Unit

  override def bulkIndex(docs: Seq[(String, JsObject)]) = Unit
  override def indexDocument(id: String, doc: JsObject) = Unit
  override def deleteDocument(id: String) = Unit

}
