package org.broadinstitute.dsde.firecloud.dataaccess

import org.apache.lucene.search.join.ScoreMode
import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.model._
import org.broadinstitute.dsde.firecloud.model.ElasticSearch._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.SamResource.{AccessPolicyName, UserPolicy}
import org.broadinstitute.dsde.rawls.model.{AttributeName, WorkspaceAccessLevels}
import org.elasticsearch.search.aggregations.{AggregationBuilders, Aggregations}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilder}
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.SearchHit
import org.elasticsearch.search.aggregations.bucket.terms.{StringTerms, Terms}
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder
import org.elasticsearch.search.sort.SortOrder
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.jdk.CollectionConverters._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex


trait ElasticSearchDAOQuerySupport extends ElasticSearchDAOSupport {

  final val HL_START = "<strong class='es-highlight'>"
  final val HL_END = "</strong>"
  final val HL_REGEX:Regex = s"$HL_START(.+?)$HL_END".r.unanchored

  final val AGG_MAX_SIZE = FireCloudConfig.ElasticSearch.maxAggregations
  final val AGG_DEFAULT_SIZE = 5

  /** ES queries - below is similar to what will be created by the query builders
    * {"query":{"match_all":{}}}"
    * {"query":{
    *    "bool":{
    *      "must":[
    *        {"bool":
    *          {"should":[
    *            {"term":{"library:indication":"n/a"}},
    *            {"term":{"library:indication":"disease"}},
    *            {"term":{"library:indication":"lukemia"}}]
    *          }
    *        }],
    *      "filter":[
    *        {"bool" :
    *          {"should" : [
    *            {"bool" :
    *              {"must_not" :
    *                {"exists" : {"field" : "_discoverableByGroups"}}}},
    *            {"terms" :
    *              {"_discoverableByGroups" : ["37e1e624-84e0-4046-98d6-f89388a8f727-OWNER", "fdae9abd-be33-4be9-9eb2-321df017b8a2-OWNER"]}},
    *            {"terms" :
    *              {"workspaceId.keyword" : ["fda1107a-c3cb-4fe7-b6af-c118fcb4ace9", "dfc032b2-6d0e-4584-a6f9-d2e93d894f1d"]}}]
    *          }
    *        }]
    *        }},
    *  "aggregations":{
    *    "library:dataUseRestriction":{
    *      "terms":{"field":"library:dataUseRestriction.keyword"}},
    *    "library:datatype":{
    *      "terms":{"field":"library:datatype.keyword"}}
    * }
    *  The outer boolean query, which is a must, is indicating that all selections of the filter and search
    *  are being "and"-ed together
    *  For selections with an attribute, each selection is "or"-ed together - this is represented by the inner
    *  boolean query with the should list
    *  Permission matching is done in a filter because there is not the same restriction on the number of terms allowed.
    */


  def createQuery(criteria: LibrarySearchParams, groups: Seq[String], workspaceIds: Seq[String], researchPurposeSupport: ResearchPurposeSupport, searchField: String = fieldAll, phrase: Boolean = false): QueryBuilder = {
    val query: BoolQueryBuilder = boolQuery // outer query, all subqueries should be added to the must list
    query.must(criteria.searchString match {
      case None => matchAllQuery
      case Some(searchTerm) if searchTerm.trim == "" => matchAllQuery
      case Some(searchTerm) =>
        val fieldSearch = if (phrase) {
          matchPhraseQuery(searchField, searchTerm)
        } else {
          matchQuery(searchField, searchTerm).minimumShouldMatch("2<67%")
        }
        boolQuery
          .should(fieldSearch)
          .should(nestedQuery("parents", matchQuery("parents.label", searchTerm).minimumShouldMatch("3<75%"), ScoreMode.Avg))
    })
    criteria.filters foreach { case (field:String, values:Seq[String]) =>
      val fieldQuery = boolQuery // query for possible values of aggregation, added via should
      values foreach { value:String => fieldQuery.should(termQuery(field+".keyword", value))}
      query.must(fieldQuery)
    }
    criteria.researchPurpose map {
      def toLibraryAttributeName(name: String): String = AttributeName.toDelimitedName(AttributeName.withLibraryNS(name))
      rp => query.must(researchPurposeSupport.researchPurposeFilters(rp, toLibraryAttributeName))
    }

    val groupsQuery = boolQuery
    // https://www.elastic.co/guide/en/elasticsearch/reference/2.4/query-dsl-exists-query.html
    groupsQuery.should(boolQuery.mustNot(existsQuery(fieldDiscoverableByGroups)))
    if (groups.nonEmpty) {
      groupsQuery.should(termsQuery(fieldDiscoverableByGroups, groups.asJavaCollection))
    }
    if (workspaceIds.nonEmpty) {
      groupsQuery.should(termsQuery(fieldWorkspaceId + ".keyword", workspaceIds.asJavaCollection))
    }
    query.filter(groupsQuery)
    query
  }

  def addAggregationsToQuery(searchReq: SearchRequestBuilder, aggFields: Map[String, Int]): SearchRequestBuilder = {
    // The UI sends 0 to indicate unbounded size for an aggregate. However, we don't actually
    // want unbounded/infinite; we impose a server-side limit here, instead of asking the UI to
    // know what the limit should be.
    val aggregates = aggFields map { case (k:String,v:Int) => if (v == 0) (k,AGG_MAX_SIZE) else (k,v) }

    aggregates.keys foreach { property: String =>
      // property here is specifying which attribute to collect aggregation info for
      // we use field.keyword here because we want it to use the unanalyzed form of the data for the aggregations
      searchReq.addAggregation(AggregationBuilders.terms(property).field(property + ".keyword").size(aggregates.getOrElse(property, AGG_DEFAULT_SIZE)))
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

    if (sortField.isDefined && sortField.get.trim.nonEmpty) {
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

  def buildSearchQuery(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String], workspaceIds: Seq[String], researchPurposeSupport: ResearchPurposeSupport): SearchRequestBuilder = {
    val searchQuery = createESSearchRequest(client, indexname, createQuery(criteria, groups, workspaceIds, researchPurposeSupport), criteria.from, criteria.size, criteria.sortField, criteria.sortDirection)
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

  def buildAutocompleteQuery(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String], workspaceIds: Seq[String], researchPurposeSupport: ResearchPurposeSupport): SearchRequestBuilder = {
    createESAutocompleteRequest(client, indexname, createQuery(criteria, groups, workspaceIds, researchPurposeSupport, searchField=fieldSuggest, phrase=true), 0, criteria.size)
  }

  def buildAggregateQueries(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String], workspaceIds: Seq[String], researchPurposeSupport: ResearchPurposeSupport): Seq[SearchRequestBuilder] = {
    // for aggregations fields that are part of the current search criteria, we need to do a separate
    // aggregate request *without* that term in the search criteria
    (criteria.fieldAggregations.keySet.toSeq intersect criteria.filters.keySet.toSeq) map { field: String =>
      val query = createQuery(criteria.copy(filters = criteria.filters - field), groups, workspaceIds, researchPurposeSupport)
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
            terms.getBuckets.asScala.toList map { bucket: Terms.Bucket =>
              AggregationTermResult(bucket.getKey.toString, bucket.getDocCount.toInt)
            }))
      }
    }
  }

  // TODO: we might want to keep a cache of workspaceIds that have been published so we can intersect with the workspaces
  // the user has access to before adding them to the filter criteria
  def findDocumentsWithAggregateInfo(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String], workspacePolicyMap: Map[String, UserPolicy], researchPurposeSupport: ResearchPurposeSupport): Future[LibrarySearchResponse] = {
    val workspaceIds = workspacePolicyMap.keys.toSeq
    val searchQuery = buildSearchQuery(client, indexname, criteria, groups, workspaceIds, researchPurposeSupport)
    val aggregateQueries = buildAggregateQueries(client, indexname, criteria, groups, workspaceIds, researchPurposeSupport)

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
      allResults.last.getHits.getTotalHits().toInt,
      allResults.last.getHits.getHits.toList map { hit: SearchHit =>
        addAccessLevel(hit.getSourceAsString.parseJson.asJsObject, workspacePolicyMap)
      },
      allResults flatMap { aggResp => getAggregationsFromResults(aggResp.getAggregations) }
    )
    response
  }

  def addAccessLevel(doc: JsObject, workspacePolicyMap: Map[String, UserPolicy]): JsObject = {
    val docId: Option[JsValue] = doc.fields.get("workspaceId")
    val accessStr = (docId match {
      case Some(wsid:JsString) =>
        workspacePolicyMap.get(wsid.value) map (_.accessPolicyName.value)
      case _ => None
    }) getOrElse WorkspaceAccessLevels.NoAccess.toString
    JsObject(doc.fields +  ("workspaceAccess" -> JsString(accessStr)))
  }

  def populateSuggestions(client: TransportClient, indexName: String, field: String, text: String) : Future[Seq[String]] = {
    /*
      goal:
        generate suggestions for populating a single field in the catalog wizard,
        based off what other users have entered for this field.
      implementation:
        perform a (prefixQuery OR matchPhrasePrefixQuery) within the target field to filter the corpus
        to the set of documents where the field has a phrase starting with the user's term.
        Then, calculate a terms aggregation, in order to return the top 10 unique values
        for the field, within our filtered corpus. Results are ordered by document count descending -
        i.e. how many times they are used in the corpus.
     */
    val keywordField = field + ".suggestKeyword" // non-analyzed variant of the field

    val prefixFilter = boolQuery()
        .should(prefixQuery(keywordField, text))
        .should(matchPhrasePrefixQuery(field, text))

    val aggregationName = "suggTerms"
    val termsAgg = AggregationBuilders.terms(aggregationName).field(keywordField).size(10)

    val suggestQuery = client.prepareSearch(indexName)
                                .setQuery(prefixFilter)
                                .addAggregation(termsAgg)
                                .setFetchSource(false)
                                .setSize(0)

    val suggestTry = Try(executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](suggestQuery))

    suggestTry match {
      case Success(suggestResult) =>
        val allAggs = suggestResult.getAggregations.asMap().asScala
        val termsAgg = allAggs.get(aggregationName)
        val buckets = termsAgg match {
          case Some(st:StringTerms) =>
            st.getBuckets.asScala.map(_.getKey.toString)
          case _ =>
            logger.warn(s"failed to get populate suggestions for field [$field] and term [$text]")
            Seq.empty[String]
        }
        Future(buckets.toList)

      case Failure(ex) =>
        logger.warn(s"failed to get populate suggestions for field [$field] and term [$text]: ${ex.getMessage}")
        Future(Seq.empty[String])
    }
  }

  def autocompleteSuggestions(client: TransportClient, indexname: String, criteria: LibrarySearchParams, groups: Seq[String], workspaceIdAccessMap: Map[String, UserPolicy], researchPurposeSupport: ResearchPurposeSupport): Future[LibrarySearchResponse] = {

    val searchQuery = buildAutocompleteQuery(client, indexname, criteria, groups, workspaceIdAccessMap.keys.toSeq, researchPurposeSupport)

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

