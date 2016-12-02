package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model._
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.elasticsearch.search.aggregations.{AggregationBuilders, Aggregations}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.search.aggregations.bucket.terms.Terms

import scala.collection.JavaConverters._
import spray.json.DefaultJsonProtocol._

import scala.collection.mutable


/**
  * Created by ahaessly on 11/28/16.
  */
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

  def addAggregations(searchReq: SearchRequestBuilder, aggFields: Seq[String], maxAggs: Option[Int]): SearchRequestBuilder = {
    aggFields foreach { field: String =>
      val terms = AggregationBuilders.terms(field)
      if (maxAggs.isDefined) {
        terms.size(maxAggs.get)
      }
      searchReq.addAggregation(terms.field(field + ".raw"))
    }
    searchReq
  }

  def queryToJsonQueryString(qmseq: Seq[QueryMap]): String = {
    ESConstantScore(ESFilter(ESBool(ESMust(qmseq)))).toJson.compactPrint
  }

  def createESSearchRequest(client: TransportClient, indexname: String, qmseq: Seq[QueryMap], from: Int, size: Int): SearchRequestBuilder = {
    client.prepareSearch(indexname)
      .setQuery(queryToJsonQueryString(qmseq))
      .setFrom(from)
      .setSize(size)
  }

  def buildMainQuery(client: TransportClient, indexname: String, baseQuery: Seq[QueryMap], criteria: LibrarySearchParams): SearchRequestBuilder = {
    val mainQuery = createESSearchRequest(client, indexname, baseQuery, criteria.from, criteria.size)
    if (criteria.fieldAggregations.nonEmpty && criteria.fieldAggregations.size != criteria.searchFields.size) {
      // if we are not collecting aggregation data (in the case of pagination)
      // and if there are some aggregations that are not part of the search criteria
      // then the aggregation data for those fields will be accurate from the standard search
      addAggregations(mainQuery, criteria.fieldAggregations.diff(criteria.searchFields.keySet.toIndexedSeq), criteria.maxAggregations)
    }
    mainQuery
  }


  def buildAggregateQueries(client: TransportClient, indexname: String, baseQuery: Seq[QueryMap], criteria: LibrarySearchParams): Map[String, SearchRequestBuilder] = {
    // for aggregtions fields that are part of the current search criteria, we need to do a separate
    // aggregate request *without* that term in the search criteria
    (criteria.searchFields.keys map { field =>
      val query = baseQuery.filterNot((m: QueryMap) => m.isInstanceOf[ESBool] && m.asInstanceOf[ESBool].bool.isInstanceOf[ESShould]
        && m.asInstanceOf[ESBool].bool.asInstanceOf[ESShould].should.last.isInstanceOf[ESTerm]
        && m.asInstanceOf[ESBool].bool.asInstanceOf[ESShould].should.last.asInstanceOf[ESTerm].term.last._1.equals(field))
      // setting size to 0, we will ignore the actual search results
      field -> addAggregations(createESSearchRequest(client, indexname, query, 0, 0), Seq(field), criteria.maxAggregations)
    }).toMap
  }

  def getAggregationsFromResults(aggResults: Aggregations): Seq[LibraryAggregationResponse] = {
    aggResults.getAsMap.keySet().asScala.toSeq map { field: String =>
      val terms: Terms = aggResults.get(field)
      LibraryAggregationResponse(terms.getName,
        AggregationFieldResults(terms.getSumOfOtherDocCounts.toInt,
          terms.getBuckets.asScala map { bucket: Terms.Bucket =>
            AggregationTermResult(bucket.getKey.toString, bucket.getDocCount.toInt)
          }))
    }
  }


  def findDocumentsWithAggregateInfo(client: TransportClient, indexname: String, criteria: LibrarySearchParams): LibrarySearchResponse = {
    val baseQuery = createQuery(criteria)
    val mainQuery = buildMainQuery(client, indexname, baseQuery, criteria)
    val aggregateQueries = buildAggregateQueries(client, indexname, baseQuery, criteria)

    // TODO execute requests in parallel
    logger.debug(s"main query: $mainQuery")
    val searchResults = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](mainQuery)

    val sourceDocuments = searchResults.getHits.getHits.toList map { hit =>
      hit.getSourceAsString.parseJson
    }
    val aggResults = mutable.ArrayBuffer[LibraryAggregationResponse]()
    aggResults ++= getAggregationsFromResults(searchResults.getAggregations)

    logger.debug(s"additional queries for aggregations: $aggregateQueries")
    aggregateQueries.values foreach {query: SearchRequestBuilder =>
      val secondaryResults = executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](query)
      aggResults ++= getAggregationsFromResults(secondaryResults.getAggregations)
    }

    LibrarySearchResponse(criteria, searchResults.getHits.totalHits().toInt, sourceDocuments, aggResults)
  }
}

