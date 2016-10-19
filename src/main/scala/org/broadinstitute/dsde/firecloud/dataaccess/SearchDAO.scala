package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model.Document

trait SearchDAO extends LazyLogging {

  def initIndex(): Unit
  def recreateIndex(): Unit
  def indexExists(): Boolean
  def createIndex(): Unit
  def deleteIndex(): Unit

  def bulkIndex(docs: Seq[Document]): Unit
  def indexDocument(doc: Document): Unit
  def deleteDocument(id: String): Unit

  def makeESMapping(json_definition: String): String

}
