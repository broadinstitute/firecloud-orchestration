package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy
import org.elasticsearch.client.{RequestOptions, RestHighLevelClient}
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.common.xcontent.XContentType
import org.parboiled.common.FileUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ElasticSearchDAO(client: RestHighLevelClient, indexName: String, researchPurposeSupport: ResearchPurposeSupport) extends SearchDAO with ElasticSearchDAOSupport with ElasticSearchDAOQuerySupport {

  lazy private final val OPTS = RequestOptions.DEFAULT

  initIndex()

  // if the index does not exist, create it.
  override def initIndex(): Unit = {
    conditionalRecreateIndex() // deleteFirst = false
  }

  // delete an existing index, then re-create it.
  override def recreateIndex(): Unit = {
    conditionalRecreateIndex(true)
  }

  override def indexExists(): Boolean = {
    val getIndexRequest = new GetIndexRequest(indexName)
    elasticSearchRequest() {
      client.indices().exists(getIndexRequest, OPTS)
    }
  }

  override def createIndex(): Unit = {

    // TODO: AJ-249: delete this entirely once we're done with it
    val UNUSED_FOR_NOW = makeMapping(FileUtils.readAllTextFromResource(LibraryService.schemaLocation))

    val createIndexRequest = new CreateIndexRequest(indexName)
    createIndexRequest.settings(analysisSettings, XContentType.JSON)
    createIndexRequest.mapping("document", mappings, XContentType.JSON)
    // TODO: set to one shard? https://www.elastic.co/guide/en/elasticsearch/guide/current/relevance-is-broken.html

    elasticSearchRequest() {
      client.indices().create(createIndexRequest, OPTS)
    }
  }

  // will throw an error if index does not exist
  override def deleteIndex(): Unit = {
    val deleteIndexRequest = new DeleteIndexRequest(indexName)
    elasticSearchRequest() {
      client.indices().delete(deleteIndexRequest, OPTS)
    }
  }

  override def bulkIndex(docs: Seq[Document], refresh: Boolean = false): LibraryBulkIndexResponse = {
    val bulkRequest = new BulkRequest(indexName)
    // only specify immediate refresh if caller specified true
    // this way, the ES client library can change its default for setRefreshPolicy, and we'll inherit the default.
    if (refresh)
      bulkRequest.setRefreshPolicy(RefreshPolicy.IMMEDIATE)
    docs map { doc: Document =>
        bulkRequest.add(
          new IndexRequest(indexName).id(doc.id).source(doc.content.compactPrint, XContentType.JSON))
    }
    val bulkResponse = elasticSearchRequest() {
      client.bulk(bulkRequest, OPTS)
    }

    val msgs:Map[String,String] = if (bulkResponse.hasFailures) {
      bulkResponse.getItems.filter(_.isFailed).map(f => f.getId -> f.getFailureMessage).toMap
    } else {
      Map.empty
    }
    LibraryBulkIndexResponse(bulkResponse.getItems.length, bulkResponse.hasFailures, msgs)
  }

  override def indexDocument(doc: Document): Unit = {
    val indexRequest = new IndexRequest(indexName)
    indexRequest.id(doc.id)
    indexRequest.source(doc.content.compactPrint, XContentType.JSON)
    elasticSearchRequest() {
      client.index(indexRequest, OPTS)
    }
  }

  override def deleteDocument(id: String): Unit = {
    val deleteRequest = new DeleteRequest(indexName, id)
    elasticSearchRequest() {
      client.delete(deleteRequest, OPTS)
    }
  }

  private def conditionalRecreateIndex(deleteFirst: Boolean = false): Unit = {
    try {
      logger.info(s"Checking to see if ElasticSearch index '%s' exists ... ".format(indexName))
      val exists = indexExists()
      logger.info(s"... ES index '%s' exists: %s".format(indexName, exists.toString))
      if (deleteFirst && exists) {
        logger.info(s"Deleting ES index '%s' before recreation ...".format(indexName))
        deleteIndex()
        logger.info(s"... ES index '%s' deleted.".format(indexName))
      }
      if (deleteFirst || !exists) {
        logger.info(s"Creating ES index '%s' ...".format(indexName))
        createIndex()
        logger.info(s"... ES index '%s' created.".format(indexName))
      }
    } catch {
      case e: Exception => logger.warn(s"ES index '%s' could not be recreated and may be in an unstable state.".format(indexName), e)
    }
  }

  override def findDocuments(criteria: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse] = {
    findDocumentsWithAggregateInfo(client, indexName, criteria, groups, workspacePolicyMap, researchPurposeSupport)
  }

  override def suggestionsFromAll(criteria: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy]): Future[LibrarySearchResponse] = {
    autocompleteSuggestions(client, indexName, criteria, groups, workspacePolicyMap, researchPurposeSupport)
  }

  override def suggestionsForFieldPopulate(field: String, text: String): Future[Seq[String]] = {
    populateSuggestions(client, indexName, field, text)
  }

  /* see https://www.elastic.co/guide/en/elasticsearch/guide/current/_index_time_search_as_you_type.html
   *  and https://qbox.io/blog/multi-field-partial-word-autocomplete-in-elasticsearch-using-ngrams
   *  for explanation of the autocomplete analyzer.
   *
   * our default analyzer is based off the english analyzer (https://www.elastic.co/guide/en/elasticsearch/reference/2.4/analysis-lang-analyzer.html#english-analyzer)
   *   but includes the word_delimiter filter for better searching on data containing underscores, e.g. "tcga_brca"
   *   
   * lazy is necessary here because we use it above
   */
  private final lazy val analysisSettings = FileUtils.readAllTextFromResource("library/es-settings.json")

  private final lazy val mappings = FileUtils.readAllTextFromResource("library/es-mappings.json")

  override def status: Future[SubsystemStatus] = {
    Future(SubsystemStatus(this.indexExists(), None))
  }
}
