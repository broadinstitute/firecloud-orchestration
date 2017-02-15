package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.slf4j.LazyLogging
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

import scala.concurrent.Future

trait SearchDAO extends LazyLogging {

  def initIndex(): Unit
  def recreateIndex(): Unit
  def indexExists(): Boolean
  def createIndex(): Unit
  def deleteIndex(): Unit

  def bulkIndex(docs: Seq[Document]): LibraryBulkIndexResponse
  def indexDocument(doc: Document): Unit
  def deleteDocument(id: String): Unit
  def findDocuments(criteria: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse]
  def suggestionsFromAll(criteria: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse]
  def suggestionsForFieldPopulate(field: String, text: String): Future[Seq[String]]
}
