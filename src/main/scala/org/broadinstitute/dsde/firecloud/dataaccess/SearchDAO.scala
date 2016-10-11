package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.Document
import spray.json.JsObject

/**
  * Created by davidan on 9/28/16.
  */
trait SearchDAO extends LazyLogging {

  def initIndex
  def recreateIndex
  def indexExists
  def createIndex
  def deleteIndex

  def bulkIndex(docs: Seq[Document])
  def indexDocument(doc: Document)
  def deleteDocument(id: String)

}
