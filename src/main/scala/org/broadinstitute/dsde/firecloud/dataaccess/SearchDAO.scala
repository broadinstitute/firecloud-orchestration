package org.broadinstitute.dsde.firecloud.dataaccess

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.rawls.model.ErrorReportSource
import org.broadinstitute.dsde.workbench.util.health.Subsystems
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem

import scala.concurrent.Future

object SearchDAO {
  lazy val serviceName = Subsystems.LibraryIndex
}

trait SearchDAO extends LazyLogging with ReportsSubsystemStatus {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource(SearchDAO.serviceName.value)

  def initIndex(): Unit
  def recreateIndex(): Unit
  def indexExists(): Boolean
  def createIndex(): Unit
  def deleteIndex(): Unit

  def bulkIndex(docs: Seq[Document], refresh:Boolean = false): LibraryBulkIndexResponse
  def indexDocument(doc: Document): Unit
  def deleteDocument(id: String): Unit
  def findDocuments(criteria: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse]
  def suggestionsFromAll(criteria: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse]
  def suggestionsForFieldPopulate(field: String, text: String): Future[Seq[String]]

  override def serviceName:Subsystem = SearchDAO.serviceName
}
