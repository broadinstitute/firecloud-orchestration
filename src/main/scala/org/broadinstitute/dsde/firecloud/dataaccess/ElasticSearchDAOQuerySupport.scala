package org.broadinstitute.dsde.firecloud.dataaccess

import org.broadinstitute.dsde.firecloud.model._
import spray.json._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._
import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.search.aggregations.AggregationBuilders

/**
  * Created by ahaessly on 11/28/16.
  */
trait ElasticSearchDAOQuerySupport {

  def createESMatch(data: Map[String, Seq[String]]): Seq[QueryMap] = {
    (data map {
      case (k: String, v: Vector[String]) => ESMatch(Map(k -> v.mkString(" ")))
    }).toSeq
  }

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

  def createQueryString(criteria: LibrarySearchParams): String = {
    val qmseq: Seq[QueryMap] = (criteria.searchString, criteria.searchFields.size) match {
      case (None | Some(""), 0) => Seq(new ESMatchAll)
      case (None | Some(""), _) => createListOfMusts(criteria.searchFields)
      case (Some(searchTerm: String), 0) => Seq(new ESMatch(searchTerm.toLowerCase))
      case (Some(searchTerm: String), _) => createListOfMusts(criteria.searchFields) :+ new ESMatch(searchTerm.toLowerCase)
    }

    ESQuery(ESConstantScore(ESFilter(ESBool(ESMust(qmseq))))).toJson.compactPrint
  }

  def addAggregations(searchReq: SearchRequestBuilder, criteria: LibrarySearchParams) = {
    criteria.fieldAggregations map { field: String =>
      val terms = AggregationBuilders.terms(field)
      if (None != criteria.maxAggregations) {
        terms.size(criteria.maxAggregations.get)
      }
      searchReq.addAggregation(terms.field(field + ".raw"))
    }
  }

}
