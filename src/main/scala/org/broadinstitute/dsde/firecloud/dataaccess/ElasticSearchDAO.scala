package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.service.LibraryService
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.delete.{DeleteIndexRequest, DeleteIndexRequestBuilder, DeleteIndexResponse}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.bulk.{BulkRequest, BulkRequestBuilder, BulkResponse}
import org.elasticsearch.action.delete.{DeleteRequest, DeleteRequestBuilder, DeleteResponse}
import org.elasticsearch.action.index.{IndexRequest, IndexRequestBuilder, IndexResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.search.aggregations.{AggregationBuilders}
import org.parboiled.common.FileUtils
import spray.http.Uri.Authority
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import scala.collection.JavaConverters._

class ElasticSearchDAO(servers:Seq[Authority], indexName: String) extends SearchDAO with ElasticSearchDAOSupport {

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
      client.admin.indices.prepareCreate(indexName).addMapping(datatype, mapping)
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
      case e: Exception => logger.warn(s"ES index '%s' could not be recreated and may be in an unstable state.".format(indexName))
    }
  }

  def createESMatch(data: Map[String, Seq[String]]): Seq[QueryMap] = {
    (data map {
      case (k:String, v:Vector[String]) => ESMatch(Map(k -> v.mkString(" ")))
    }).toSeq
  }

  override def findDocuments(criteria: LibrarySearchParams) : LibrarySearchResponse = {
    val qmseq:Seq[QueryMap] = (criteria.searchTerm, criteria.fieldTerms.size) match {
      case (None | Some(""), 0) => Seq(new ESMatchAll)
      case (None | Some(""), _) => createESMatch(criteria.fieldTerms)
      case (Some(searchTerm: String), 0) => Seq(new ESWildcard(searchTerm.toLowerCase))
      case (Some(searchTerm: String), _) => createESMatch(criteria.fieldTerms) :+ new ESWildcard(searchTerm.toLowerCase)
    }

    val searchStr = ESQuery(ESConstantScore(ESFilter(ESBool(ESMust(qmseq))))).toJson.compactPrint

    val searchReq = client.prepareSearch(indexName).setQuery(searchStr)
      .setFrom(criteria.from)
      .setSize(criteria.size)
    val searchResults = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder] (searchReq)

    val sourceDocuments = searchResults.getHits.getHits.toList map { hit =>
      hit.getSourceAsString.parseJson
    }
    new LibrarySearchResponse(criteria, searchResults.getHits.totalHits().toInt, sourceDocuments)
  }

  def getAggregations(fields: Seq[String], numBuckets: Option[Int]) : Seq[LibraryAggregationResponse] = {
    val searchReq = client.prepareSearch(indexName)
    searchReq.setSize(0)
    fields map { field: String =>
      val terms = AggregationBuilders.terms(field)
      if (None != numBuckets) {
        terms.size(numBuckets.get)
      }
      searchReq.addAggregation(terms.field(field + ".raw"))
    }
    val aggResults = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder] (searchReq)

    fields map { field: String =>
      val terms: Terms = aggResults.getAggregations().get(field)
      LibraryAggregationResponse(terms.getName(),
        AggregationFieldResults(terms.getSumOfOtherDocCounts.toInt,
          terms.getBuckets.asScala.toSeq map { bucket: Terms.Bucket =>
            AggregationTermResult(bucket.getKey.toString, bucket.getDocCount.toInt)
          }))
    }
  }
}
