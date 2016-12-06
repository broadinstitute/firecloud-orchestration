package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.elasticsearch.search.aggregations.{AggregationBuilders, Aggregations}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilder}
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global


trait ElasticSearchDAOQuerySupport extends ElasticSearchDAOSupport {

  /** ES queries - below is similar to what will be created by the query builders
    * {"query":{"match_all":{}}}"
    * {"query":{
    *    "bool":{
    *      "must":[{
    *        "bool":{
    *        "should":[
    *          {"term":{"library:indication":"n/a"}},
    *          {"term":{"library:indication":"disease"}},
    *          {"term":{"library:indication":"lukemia"}}]
    *        }
    *      },
    *      {"match":{"all_":"broad"}}]}},
    *  "aggregations":{
    *    "library:dataUseRestriction":{
    *      "terms":{"field":"library:dataUseRestriction.raw"}},
    *    "library:datatype":{
    *      "terms":{"field":"library:datatype.raw"}}
    * }
    *  The outer boolean query, which is a must, is indicating that all selections of the filter and search
    *  are being "and"-ed together
    *  For selections with an attribute, each selection is "or"-ed together - this is represented by the inner
    *  boolean query with the should list
    */


  def createQuery(criteria: LibrarySearchParams): QueryBuilder = {
    val query: BoolQueryBuilder = boolQuery // outer query, all subqueries should be added to the must list
    query.must(criteria.searchString match {
      case None => matchAllQuery
      case Some(searchTerm) if searchTerm.trim == "" => matchAllQuery
      case Some(searchTerm) => matchQuery("_all", searchTerm)
    })
    criteria.searchFields foreach { case (field:String, values:Seq[String]) =>
      val fieldQuery = boolQuery // query for possible values of aggregation, added via should
      values foreach { value:String => fieldQuery.should(termQuery(field, value))}
      query.must(fieldQuery)
    }
    query
  }

  def addAggregationsToQuery(searchReq: SearchRequestBuilder, aggFields: Seq[String], maxAggs: Option[Int]): SearchRequestBuilder = {
    aggFields foreach { field: String =>
      val terms = AggregationBuilders.terms(field)
      if (maxAggs.isDefined) {
        terms.size(maxAggs.get)
      }
      searchReq.addAggregation(terms.field(field + ".raw"))
    }
    searchReq
  }

  def createESSearchRequest(client: TransportClient, indexname: String, qmseq: QueryBuilder, from: Int, size: Int): SearchRequestBuilder = {
    client.prepareSearch(indexname)
      .setQuery(qmseq)
      .setFrom(from)
      .setSize(size)
  }

  def buildSearchQuery(client: TransportClient, indexname: String, criteria: LibrarySearchParams): SearchRequestBuilder = {
    val searchQuery = createESSearchRequest(client, indexname, createQuery(criteria), criteria.from, criteria.size)
    // if we are not collecting aggregation data (in the case of pagination), we can skip adding aggregations
    // if the search criteria contains elements from all of the aggregatable attributes, then we will be making
    // separate queries for each of them. so we can skip adding them in the main search query
    if (criteria.fieldAggregations.nonEmpty && criteria.fieldAggregations.size != criteria.searchFields.size) {
      // for the aggregations that are not part of the search criteria
      // then the aggregation data for those fields will be accurate from the main search query so we add them here
      addAggregationsToQuery(searchQuery, criteria.fieldAggregations.diff(criteria.searchFields.keySet.toSeq), criteria.maxAggregations)
    }
    searchQuery
  }


  def buildAggregateQueries(client: TransportClient, indexname: String, criteria: LibrarySearchParams): Seq[SearchRequestBuilder] = {
    // for aggregations fields that are part of the current search criteria, we need to do a separate
    // aggregate request *without* that term in the search criteria
    (criteria.fieldAggregations intersect criteria.searchFields.keySet.toSeq) map { field =>
      val query = createQuery(criteria.copy(searchFields = criteria.searchFields - field))
      // setting size to 0, we will ignore the actual search results
      addAggregationsToQuery(createESSearchRequest(client, indexname, query, 0, 0), Seq(field), criteria.maxAggregations)
    }
  }

  def getAggregationsFromResults(aggResults: Aggregations): Seq[LibraryAggregationResponse] = {
    if (aggResults == null)
      Seq.empty
    else {
      aggResults.getAsMap.keySet().asScala.toSeq map { field: String =>
        val terms: Terms = aggResults.get(field)
        LibraryAggregationResponse(terms.getName,
          AggregationFieldResults(terms.getSumOfOtherDocCounts.toInt,
            terms.getBuckets.asScala map { bucket: Terms.Bucket =>
              AggregationTermResult(bucket.getKey.toString, bucket.getDocCount.toInt)
            }))
      }
    }
  }

  def findDocumentsWithAggregateInfo(client: TransportClient, indexname: String, criteria: LibrarySearchParams): Future[LibrarySearchResponse] = {
    val searchQuery = buildSearchQuery(client, indexname, criteria)
    val aggregateQueries = buildAggregateQueries(client, indexname, criteria)


    logger.debug(s"main search query: $searchQuery.toJson")
    // search future will request aggregate data for aggregatable attributes that are not being searched on
    val searchFuture = Future[SearchResponse](executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](searchQuery))

    logger.debug(s"additional queries for aggregations: $aggregateQueries.toJson")
    val aggFutures:Seq[Future[SearchResponse]] = aggregateQueries map {query: SearchRequestBuilder =>
      Future[SearchResponse](executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](query))
    }

    val allFutures = Future.sequence(aggFutures :+ searchFuture)
    val response = for (
      allResults <- allFutures
    ) yield LibrarySearchResponse(
      criteria,
      allResults.last.getHits.totalHits().toInt,
      allResults.last.getHits.getHits.toList map { hit => hit.getSourceAsString.parseJson },
      allResults flatMap { aggResp => getAggregationsFromResults(aggResp.getAggregations) }
    )
    response
  }
}

