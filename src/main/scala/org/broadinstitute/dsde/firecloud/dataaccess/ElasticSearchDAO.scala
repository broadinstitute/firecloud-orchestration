package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.delete.{DeleteIndexRequest, DeleteIndexRequestBuilder, DeleteIndexResponse}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.admin.indices.mapping.put.{PutMappingRequest, PutMappingRequestBuilder, PutMappingResponse}
import org.elasticsearch.action.bulk.{BulkRequest, BulkRequestBuilder, BulkResponse}
import org.elasticsearch.action.delete.{DeleteRequest, DeleteRequestBuilder, DeleteResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexRequestBuilder, IndexResponse}
import org.elasticsearch.client.transport.TransportClient
import org.parboiled.common.FileUtils
import spray.http.Uri.Authority
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.concurrent.Future

class ElasticSearchDAO(servers: Seq[Authority], indexName: String) extends SearchDAO with ElasticSearchDAOSupport with ElasticSearchDAOQuerySupport {

  private val client: TransportClient = buildClient(servers)
  private final val datatype = "dataset"

  initIndex

  // if the index does not exist, create it.
  override def initIndex = {
    conditionalRecreateIndex(false)
  }

  // delete an existing index, then re-create it.
  override def recreateIndex = {
    conditionalRecreateIndex(true)
  }

  override def indexExists: Boolean = {
    executeESRequest[IndicesExistsRequest, IndicesExistsResponse, IndicesExistsRequestBuilder](
      client.admin.indices.prepareExists(indexName)
    ).isExists
  }

  override def createIndex = {
    val mapping = makeMapping(FileUtils.readAllTextFromResource(LibraryService.schemaLocation))
    executeESRequest[CreateIndexRequest, CreateIndexResponse, CreateIndexRequestBuilder](
      client.admin.indices.prepareCreate(indexName)
        .setSettings(analysisSettings)
        .addMapping(datatype, mapping)
      // TODO: set to one shard? https://www.elastic.co/guide/en/elasticsearch/guide/current/relevance-is-broken.html
    )
  }

  // will throw an error if index does not exist
  override def deleteIndex = {
    executeESRequest[DeleteIndexRequest, DeleteIndexResponse, DeleteIndexRequestBuilder](
      client.admin.indices.prepareDelete(indexName)
    )
  }

  override def bulkIndex(docs: Seq[Document]) = {
    val bulkRequest = client.prepareBulk
    docs map {
      case (doc:Document) => bulkRequest.add(client.prepareIndex(indexName, datatype, doc.id).setSource(doc.content.compactPrint))
    }
    val bulkResponse = executeESRequest[BulkRequest, BulkResponse, BulkRequestBuilder](bulkRequest)

    if (bulkResponse.hasFailures) {
      logger.warn(bulkResponse.buildFailureMessage)
    }
    bulkResponse.buildFailureMessage
  }

  override def indexDocument(doc: Document) = {
    executeESRequest[IndexRequest, IndexResponse, IndexRequestBuilder] (
      client.prepareIndex(indexName, datatype, doc.id).setSource(doc.content.compactPrint)
    )
  }

  override def deleteDocument(id: String) = {
    executeESRequest[DeleteRequest, DeleteResponse, DeleteRequestBuilder] (
      client.prepareDelete(indexName, datatype, id)
    )
  }

  private def conditionalRecreateIndex(deleteFirst: Boolean = false) = {
    try {
      logger.info(s"Checking to see if ElasticSearch index '%s' exists ... ".format(indexName))
      val exists = indexExists
      logger.info(s"... ES index '%s' exists: %s".format(indexName, exists.toString))
      if (deleteFirst && exists) {
        logger.info(s"Deleting ES index '%s' before recreation ...".format(indexName))
        deleteIndex
        logger.info(s"... ES index '%s' deleted.".format(indexName))
      }
      if (deleteFirst || !exists) {
        logger.info(s"Creating ES index '%s' ...".format(indexName))
        createIndex
        logger.info(s"... ES index '%s' created.".format(indexName))
      }
    } catch {
      case e: Exception => logger.warn(s"ES index '%s' could not be recreated and may be in an unstable state.".format(indexName), e)
    }
  }

  override def findDocuments(criteria: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {
    findDocumentsWithAggregateInfo(client, indexName, criteria, groups)
  }

  override def suggest(criteria: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {
    autocompleteSuggestions(client, indexName, criteria, groups)
  }

  override def fieldSuggest(field: String, text: String): Future[Seq[String]] = {
    allSuggestions(client, indexName, field, text)
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
}
