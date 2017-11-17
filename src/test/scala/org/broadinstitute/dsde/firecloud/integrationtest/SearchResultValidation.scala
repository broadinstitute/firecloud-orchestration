package org.broadinstitute.dsde.firecloud.integrationtest

import org.broadinstitute.dsde.firecloud.integrationtest.ESIntegrationSupport.{emptyCriteria, searchDAO}
import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.broadinstitute.dsde.firecloud.model.LibrarySearchResponse
import org.scalatest.Assertions._
import spray.json.JsValue
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, MINUTES}

trait SearchResultValidation {

  val dur = Duration(2, MINUTES)

  def searchFor(txt:String) = {
    val criteria = emptyCriteria.copy(searchString = Some(txt))
    Await.result(searchDAO.findDocuments(criteria, Seq.empty[String]), dur)
  }

  def searchWithPurpose(researchPurpose: Option[ResearchPurpose], term:Option[String], filters:Option[Map[String, Seq[String]]]): LibrarySearchResponse = {
    val criteria = emptyCriteria.copy(
      searchString = term,
      researchPurpose = researchPurpose,
      filters = filters.getOrElse(Map.empty[String, Seq[String]])
    )
    // set size to 100 to make sure we return all results for testing comparisons
    Await.result(searchDAO.findDocuments(criteria.copy(size=100), Seq.empty[String]), dur)
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
    Await.result(searchDAO.suggestionsFromAll(criteria.copy(size=100), Seq.empty[String]), dur)
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

  def getResultField(attrName:String, response:LibrarySearchResponse):Set[String] = {
    (response.results map {jsval:JsValue =>
      jsval.asJsObject.fields(attrName).convertTo[String]
    }).toSet
  }

}
