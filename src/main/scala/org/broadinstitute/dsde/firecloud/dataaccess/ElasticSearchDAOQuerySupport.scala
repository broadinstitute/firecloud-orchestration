package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.elasticsearch.search.aggregations.{AggregationBuilders, Aggregations}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global


trait ElasticSearchDAOQuerySupport extends ElasticSearchDAOSupport {

  def createListOfMusts(fields: Map[String, Seq[String]]): Seq[QueryMap] = {
    (fields map {
      case (k, v) => ESBool(createESShouldForTerms(k, v))
    }).toSeq
  }

  def createESShouldForTerms(attribute: String, terms: Seq[String]): ESShould = {
    val clauses: Seq[ESTerm] = terms map {
      case (term: String) => ESTerm(Map(attribute -> term))
    }
    ESShould(clauses)
  }

  def createQuery(criteria: LibrarySearchParams): Seq[QueryMap] = {
    (criteria.searchString, criteria.searchFields.size) match {
      case (None | Some(""), 0) => Seq(new ESMatchAll)
      case (None | Some(""), _) => createListOfMusts(criteria.searchFields)
      case (Some(searchTerm: String), 0) => Seq(new ESMatch(searchTerm.toLowerCase))
      case (Some(searchTerm: String), _) => createListOfMusts(criteria.searchFields) :+ new ESMatch(searchTerm.toLowerCase)
    }
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

  def createESSearchRequest(client: TransportClient, indexname: String, qmseq: Seq[QueryMap], from: Int, size: Int): SearchRequestBuilder = {
    client.prepareSearch(indexname)
      .setQuery(ESConstantScore(ESFilter(ESBool(ESMust(qmseq)))).toJson.compactPrint)
      .setFrom(from)
      .setSize(size)
  }

  def buildMainQuery(client: TransportClient, indexname: String, baseQuery: Seq[QueryMap], criteria: LibrarySearchParams): SearchRequestBuilder = {
    val mainQuery = createESSearchRequest(client, indexname, baseQuery, criteria.from, criteria.size)
    if (criteria.fieldAggregations.nonEmpty && criteria.fieldAggregations.size != criteria.searchFields.size) {
      // if we are not collecting aggregation data (in the case of pagination)
      // and if there are some aggregations that are not part of the search criteria
      // then the aggregation data for those fields will be accurate from the main search
      addAggregationsToQuery(mainQuery, criteria.fieldAggregations.diff(criteria.searchFields.keySet.toIndexedSeq), criteria.maxAggregations)
    }
    mainQuery
  }


  def buildAggregateQueries(client: TransportClient, indexname: String, baseQuery: Seq[QueryMap], criteria: LibrarySearchParams): Seq[SearchRequestBuilder] = {
    // for aggregtions fields that are part of the current search criteria, we need to do a separate
    // aggregate request *without* that term in the search criteria
    (criteria.searchFields.keys map { field =>
      val query = baseQuery.filterNot((m: QueryMap) => m.isInstanceOf[ESBool] && m.asInstanceOf[ESBool].bool.isInstanceOf[ESShould]
        && m.asInstanceOf[ESBool].bool.asInstanceOf[ESShould].should.last.isInstanceOf[ESTerm]
        && m.asInstanceOf[ESBool].bool.asInstanceOf[ESShould].should.last.asInstanceOf[ESTerm].term.last._1.equals(field))
      // setting size to 0, we will ignore the actual search results
      addAggregationsToQuery(createESSearchRequest(client, indexname, query, 0, 0), Seq(field), criteria.maxAggregations)
    }).toSeq
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
    val baseQuery = createQuery(criteria)
    val mainQuery = buildMainQuery(client, indexname, baseQuery, criteria)
    val aggregateQueries = buildAggregateQueries(client, indexname, baseQuery, criteria)

    logger.debug(s"main query: $mainQuery")
    val searchFuture = Future[SearchResponse](executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](mainQuery))

    logger.debug(s"additional queries for aggregations: $aggregateQueries")
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

