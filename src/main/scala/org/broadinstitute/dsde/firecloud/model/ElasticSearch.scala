package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.AttributeFormat
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

object ElasticSearch {
  final val fieldAll = "_all"
  final val fieldSuggest = "_suggest"
  final val fieldDiscoverableByGroups = "_discoverableByGroups"
}

case class AttributeDefinition(properties: Map[String, AttributeDetail])

case class AttributeDetail(
  `type`: String,
  items: Option[AttributeDetail] = None,
  aggregate: Option[JsObject] = None,
  indexable: Option[Boolean] = None
)


case class ESDatasetProperty(properties: Map[String, ESPropertyFields])

// trait def, with util factories
trait ESPropertyFields {
  def suggestField(`type`:String) = ESInnerField(`type`,
    analyzer = Some("autocomplete"),
    search_analyzer = Some("standard"),
    include_in_all = Some(false),
    store = Some(true)
  )
  def rawField(`type`:String) = ESInnerField(`type`,
    index = Some("not_analyzed")
  )
}

// top-level field defs, for facet and non-facet types
case class ESType(`type`: String, copy_to: String) extends ESPropertyFields
object ESType extends ESPropertyFields {
  def apply(`type`: String):ESType = ESType(`type`, ElasticSearch.fieldSuggest)
}

case class ESInternalType(
  `type`: String,
  index: String = "not_analyzed",
  include_in_all: Boolean = false) extends ESPropertyFields

case class ESAggregatableType(`type`: String, fields: Map[String,ESInnerField], copy_to: String = ElasticSearch.fieldSuggest) extends ESPropertyFields
object ESAggregatableType extends ESPropertyFields {
  def apply(`type`: String):ESAggregatableType = ESAggregatableType(`type`, Map("raw" -> rawField(`type`)))
}

// def for ElasticSearch's multi-fields: https://www.elastic.co/guide/en/elasticsearch/reference/2.4/multi-fields.html
// technically, the top-level fields and inner fields are the same thing, and we *could* use the same class.
// we keep them different here for ease of development and clarity of code-reading.
case class ESInnerField(`type`: String,
                        analyzer: Option[String] = None,
                        search_analyzer: Option[String] = None,
                        index: Option[String] = None,
                        include_in_all: Option[Boolean] = None,
                        store: Option[Boolean] = None) extends ESPropertyFields

// classes for sending documents to ES to be indexed
trait Indexable {
  def id: String

  def content: JsObject
}

case class Document(val id: String, val content: JsObject) extends Indexable

object Document {
  def apply(id: String, valMap: AttributeMap) = {
    implicit val impAttributeFormat: AttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
    new Document(id, valMap.toJson.asJsObject)
  }

  def apply(id: String, jsonStr: String) = new Document(id, jsonStr.parseJson.asJsObject)
}


// classes to convert from json body and to json response

/**
  *
  * @param searchString
  * @param filters a map of field names to a list of possible values which will be part of the search criteria
  * @param fieldAggregations a map of the field names for which to retrieve aggregations, results will differ based on the search criteria, the value is number of aggregations to return. 0 returns all
  * @param from used for pagination, where to start the returned results
  * @param size used for pagination, how many results to return
  */
case class LibrarySearchParams(
  searchString: Option[String],
  filters: Map[String, Seq[String]],
  fieldAggregations: Map[String, Int],
  from: Int = 0,
  size: Int = 10)

object LibrarySearchParams {
  def apply(searchString: Option[String], filters: Map[String, Seq[String]], fieldAggregations: Map[String, Int], from: Option[Int], size: Option[Int]) = {
    new LibrarySearchParams(searchString, filters, fieldAggregations, from.getOrElse(0), size.getOrElse(10))
  }
}

case class LibrarySearchResponse(
  searchParams: LibrarySearchParams,
  total: Int,
  results: Seq[JsValue],
  aggregations: Seq[LibraryAggregationResponse])

case class LibraryAggregationResponse(
  field: String,
  results: AggregationFieldResults)

case class AggregationFieldResults(
  numOtherDocs: Int,
  buckets: Seq[AggregationTermResult])

case class AggregationTermResult(key: String, doc_count: Int)


