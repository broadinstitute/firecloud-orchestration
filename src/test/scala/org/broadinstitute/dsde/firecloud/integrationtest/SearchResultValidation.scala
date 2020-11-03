package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.dataaccess.{ElasticSearchDAO, MockResearchPurposeSupport}
import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport._
import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.broadinstitute.dsde.firecloud.model.LibrarySearchResponse
import org.broadinstitute.dsde.firecloud.model.SamResource.UserPolicy
import org.elasticsearch.action.search.{SearchRequest, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.index.query.BoolQueryBuilder
import org.scalatest.Assertions._
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, MINUTES}

trait SearchResultValidation {

  val dur = Duration(2, MINUTES)

  def searchFor(txt:String) = {
    val criteria = emptyCriteria.copy(searchString = Some(txt))
    Await.result(searchDAO.findDocuments(criteria, Seq.empty[String], Map.empty), dur)
  }

  def searchWithPurpose(researchPurpose: Option[ResearchPurpose], term:Option[String], filters:Option[Map[String, Seq[String]]]): LibrarySearchResponse = {
    val criteria = emptyCriteria.copy(
      searchString = term,
      researchPurpose = researchPurpose,
      filters = filters.getOrElse(Map.empty[String, Seq[String]])
    )
    // set size to 100 to make sure we return all results for testing comparisons
    Await.result(searchDAO.findDocuments(criteria.copy(size=100), Seq.empty[String], Map.empty), dur)
  }

  def searchWithPurpose(researchPurpose: ResearchPurpose): LibrarySearchResponse =
    searchWithPurpose(Some(researchPurpose), None, None)

  def searchWithPurpose(researchPurpose: ResearchPurpose, term: String): LibrarySearchResponse =
    searchWithPurpose(Some(researchPurpose), Some(term), None)

  def searchWithPurpose(researchPurpose: ResearchPurpose, filters: Map[String, Seq[String]]): LibrarySearchResponse =
    searchWithPurpose(Some(researchPurpose), None, Some(filters))

  def suggestWithPurpose(researchPurpose: ResearchPurpose, term: String) = {
    val criteria = emptyCriteria.copy(
      searchString = Some(term),
      researchPurpose = Some(researchPurpose))
    // set size to 100 to make sure we return all results for testing comparisons
    Await.result(searchDAO.suggestionsFromAll(criteria.copy(size=100), Seq.empty[String], Map.empty), dur)
  }

  /**
    * Mimics a 3rd party searching against an ElasticSearch instance using a research purpose filter
    * from us (coincidentally using the same prefix we use). Our SearchDAO is used to create and
    * execute the search request, but the research purpose is fetched identically to how a 3rd party
    * would.
    */
  def searchWithResearchPurposeQuery(researchPurpose: ResearchPurpose): SearchResponse = {
    val boolQuery: BoolQueryBuilder = researchPurposeSupport.researchPurposeFilters(researchPurpose, name => "library:" + name)

    // Use a MockResearchPurposeSupport here to prove that it's using the query created above
    val elasticSearchDAO = new ElasticSearchDAO(client, itTestIndexName, new MockResearchPurposeSupport)
    val searchRequest = elasticSearchDAO.createESSearchRequest(client, itTestIndexName, boolQuery, 0, 10)
    elasticSearchDAO.executeESRequest[SearchRequest, SearchResponse, SearchRequestBuilder](searchRequest)
  }

  def searchWithFilter(workspacePolicyMap: Map[String, UserPolicy]) = {
    Await.result(searchDAO.findDocuments(emptyCriteria, Seq.empty[String], workspacePolicyMap), dur)
  }

  def validateResultNames(expectedNames:Set[String], response:LibrarySearchResponse) = {
    validateResultField("library:datasetName", expectedNames, response)
  }

  def validateResultIndications(expectedIndications:Set[String], response:LibrarySearchResponse) = {
    validateResultField("library:indication", expectedIndications, response)
  }

  def validateSuggestions(expectedSuggestions:Set[String], response:LibrarySearchResponse) = {
    validateResultField("suggestion", expectedSuggestions, response)
  }

  def validateResultField(attrName:String, expectedValues:Set[String], response:LibrarySearchResponse) = {
    val actualValues:Set[String] = getResultField(attrName, response)
    assertResult(expectedValues) {actualValues}
  }

  def validateResultNames(expectedNames: Set[String], response: SearchResponse): Unit = {
    val results: List[JsValue] = response.getHits.getHits.toList map {
      _.getSourceAsString.parseJson
    }
    val names = getResultField("library:datasetName", results)
    assertResult(expectedNames) {names}
  }

  def getResultField(attrName:String, response:LibrarySearchResponse):Set[String] = {
    getResultField(attrName, response.results)
  }

  def getResultField(attrName: String, results: Seq[JsValue]) = {
    (results map {jsval:JsValue =>
      jsval.asJsObject.fields(attrName).convertTo[String]
    }).toSet
  }
}
