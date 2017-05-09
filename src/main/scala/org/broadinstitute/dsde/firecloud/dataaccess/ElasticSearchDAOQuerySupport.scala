package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ElasticSearch._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.elasticsearch.search.aggregations.{AggregationBuilders, Aggregations}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilder}
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder
import org.elasticsearch.search.sort.SortOrder
import org.elasticsearch.search.suggest.{SuggestBuilder, SuggestBuilders}
import org.elasticsearch.search.suggest.completion.{CompletionSuggestion}
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.matching.Regex


trait ElasticSearchDAOQuerySupport extends ElasticSearchDAOSupport {

  final val HL_START = "<strong class='es-highlight'>"
  final val HL_END = "</strong>"
  final val HL_REGEX:Regex = s"$HL_START(.+?)$HL_END".r.unanchored

  /** ES queries - below is similar to what will be created by the query builders
    * {"query":{"match_all":{}}}"
    * {"query":{
    *    "bool":{
    *      "must":[
    *        {"bool" :
    *          {"should" : [
    *            {"bool" :
    *              {"must_not" :
    *                {"exists" : {"field" : "_discoverableByGroups"}}}},
    *            {"terms" :
    *            {"_discoverableByGroups" : ["37e1e624-84e0-4046-98d6-f89388a8f727-OWNER", "fdae9abd-be33-4be9-9eb2-321df017b8a2-OWNER"]}}]
    *          }
    *        },
    *        {"bool":
    *          {"should":[
    *            {"term":{"library:indication":"n/a"}},
    *            {"term":{"library:indication":"disease"}},
    *            {"term":{"library:indication":"lukemia"}}]
    *          }
    *        },
    *        {"match":{"all_":"broad"}}]}},
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


  def createQuery(criteria: LibrarySearchParams, groups: Seq[String], searchField: String = fieldAll, phrase: Boolean = false): QueryBuilder = {
    val query: BoolQueryBuilder = boolQuery // outer query, all subqueries should be added to the must list
    query.must(criteria.searchString match {
      case None => matchAllQuery
      case Some(searchTerm) if searchTerm.trim == "" => matchAllQuery
      case Some(searchTerm) =>
        val fieldSearch = if (phrase) {
          matchPhraseQuery(searchField, searchTerm)
            //.minimumShouldMatch("2<67%")
        } else {
          matchQuery(searchField, searchTerm).minimumShouldMatch("2<67%")
        }
        boolQuery
          .should(fieldSearch)
          .should(nestedQuery("parents", matchQuery("parents.label", searchTerm).minimumShouldMatch("3<75%")))
    })
    val groupsQuery = boolQuery
    // https://www.elastic.co/guide/en/elasticsearch/reference/2.4/query-dsl-exists-query.html
    groupsQuery.should(boolQuery.mustNot(existsQuery(fieldDiscoverableByGroups)))
    if (groups.nonEmpty) {
      groupsQuery.should(termsQuery(fieldDiscoverableByGroups, groups.asJavaCollection))
    }
    query.must(groupsQuery)
    criteria.filters foreach { case (field:String, values:Seq[String]) =>
      val fieldQuery = boolQuery // query for possible values of aggregation, added via should
      values foreach { value:String => fieldQuery.should(termQuery(field+".raw", value))}
      query.must(fieldQuery)
    }
    query
  }

  def addAggregationsToQuery(searchReq: SearchRequestBuilder, aggFields: Map[String, Int]): SearchRequestBuilder = {
    aggFields.keys foreach { property: String =>
      // property here is specifying which attribute to collect aggregation info for
      // we use field.raw here because we want it to use the unanalyzed form of the data for the aggregations
      searchReq.addAggregation(AggregationBuilders.terms(property).field(property + ".raw").size(aggFields.getOrElse(property, 5)))
    }
    searchReq
  }

  def createESSearchRequest(client: TransportClient, indexname: String, qmseq: QueryBuilder, from: Int, size: Int): SearchRequestBuilder = {
    createESSearchRequest(client, indexname, qmseq, from, size, None, None)
  }

  def createESSearchRequest(client: TransportClient, indexname: String, qmseq: QueryBuilder, from: Int, size: Int, sortField: Option[String], sortDirection: Option[String]): SearchRequestBuilder = {
    val search = client.prepareSearch(indexname)
      .setQuery(qmseq)
      .setFrom(from)
      .setSize(size)

    if (sortField.isDefined) {
      val direction = sortDirection match {
        case Some("desc") => SortOrder.DESC
        case _ => SortOrder.ASC
      }
      search.addSort(sortField.get + ".sort", direction)
    }

    search
  }

  def createESAutocompleteRequest(client: TransportClient, indexname: String, qmseq: QueryBuilder, from: Int, size: Int): SearchRequestBuilder = {
    val hb = new HighlightBuilder()
      .field(fieldSuggest).fragmentSize(50)
      .preTags(HL_START).postTags(HL_END)

    createESSearchRequest(client, indexname, qmseq, from, size)
      .setFetchSource(false).highlighter(hb)
  }

  def buildSearchQuery(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String]): SearchRequestBuilder = {
    val searchQuery = createESSearchRequest(client, indexname, createQuery(criteria, groups), criteria.from, criteria.size, criteria.sortField, criteria.sortDirection)
    // if we are not collecting aggregation data (in the case of pagination), we can skip adding aggregations
    // if the search criteria contains elements from all of the aggregatable attributes, then we will be making
    // separate queries for each of them. so we can skip adding them in the main search query
    if (criteria.fieldAggregations.nonEmpty) {
      val fieldDiffs = criteria.fieldAggregations -- criteria.filters.keySet
      if (fieldDiffs.nonEmpty) {
        // for the aggregations that are not part of the search criteria
        // then the aggregation data for those fields will be accurate from the main search query so we add them here
        addAggregationsToQuery(searchQuery, fieldDiffs)
      }
    }
    searchQuery
  }

  def buildAutocompleteQuery(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String]): SearchRequestBuilder = {
    createESAutocompleteRequest(client, indexname, createQuery(criteria, groups, searchField=fieldSuggest, phrase=true), 0, 8)
  }

  def buildAggregateQueries(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String]): Seq[SearchRequestBuilder] = {
    // for aggregations fields that are part of the current search criteria, we need to do a separate
    // aggregate request *without* that term in the search criteria
    (criteria.fieldAggregations.keySet.toSeq intersect criteria.filters.keySet.toSeq) map { field: String =>
      val query = createQuery(criteria.copy(filters = criteria.filters - field), groups)
      // setting size to 0, we will ignore the actual search results
      addAggregationsToQuery(
        createESSearchRequest(client, indexname, query, 0, 0),
        // using filter instead of filterKeys which is not reliable
        criteria.fieldAggregations.filter({case (key, value) => key == field}))
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

  def findDocumentsWithAggregateInfo(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {
    val searchQuery = buildSearchQuery(client, indexname, criteria, groups)
    val aggregateQueries = buildAggregateQueries(client, indexname, criteria, groups)

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

  def populateSuggestions(client: TransportClient, indexName: String, field: String, text: String) : Future[Seq[String]] = {
    val suggestionName = "populateSuggestion"
    val suggestion = new SuggestBuilder()
        .addSuggestion(suggestionName,
          SuggestBuilders.completionSuggestion(field + ".suggest")
            .text(text))

    val suggestQuery = client.prepareSearch(indexName).suggest(suggestion)

    logger.debug(s"populate suggestions query: $suggestQuery.toJson")
    val results = Future[SearchResponse](executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](suggestQuery))

    results map { suggestResult =>
      val compSugg: CompletionSuggestion = suggestResult.getSuggest.getSuggestion(suggestionName)
      val options : Seq[CompletionSuggestion.Entry.Option] = compSugg.getEntries.get(0).getOptions.asScala
      options map { option: CompletionSuggestion.Entry.Option => option.getText.string }
    }
  }

  def autocompleteSuggestions(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String]): Future[LibrarySearchResponse] = {

    val searchQuery = buildAutocompleteQuery(client, indexname, criteria, groups)

    logger.debug(s"autocomplete search query: $searchQuery.toJson")
    val searchFuture = Future[SearchResponse](executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](searchQuery))

    searchFuture map {searchResult =>
      // autocomplete query can return duplicate suggestions. De-dupe them here.
      val suggestions:List[JsObject] = (searchResult.getHits.getHits.toList flatMap { hit =>
        if (hit.getHighlightFields.containsKey(fieldSuggest)) {
          hit.getHighlightFields.get(fieldSuggest).fragments map {t =>

            val normalized = t.toString.toLowerCase
            val stripped = stripHighlight(normalized)

            val resultFields = Map(
              "suggestion" -> JsString(stripped)
            ) ++ (findHighlight(normalized) match {
              case Some(x) => Map("highlight" -> JsString(x))
              case _ => Map.empty
            })

            JsObject(resultFields)
          }
        } else {
          None
        }
      }).distinct

      LibrarySearchResponse(
        criteria,
        suggestions.size,
        suggestions,
        Seq.empty)
    }
  }

  def stripHighlight(txt:String): String = {
    txt.replace(HL_START,"").replace(HL_END,"")
  }

  def findHighlight(txt:String): Option[String] = {
    txt match {
      case HL_REGEX(hlt) => Some(hlt)
      case _ => None
    }
  }


}

