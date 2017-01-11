package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.AttributeFormat
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

case class AttributeDefinition(properties: Map[String, AttributeDetail])

case class AttributeDetail(
  `type`: String,
  items: Option[AttributeDetail] = None,
  aggregate: Option[JsObject] = None,
  indexable: Option[Boolean] = None
)


trait ESPropertyFields
case class ESDatasetProperty(properties: Map[String, ESPropertyFields])
case class ESType(`type`: String, copy_to: String = "_suggest") extends ESPropertyFields
case class ESAggregatableType(`type`: String, fields: ESRaw, copy_to: String = "_suggest") extends ESPropertyFields {
  def this(str: String) = this(str, new ESRaw(str))
}
case class ESRaw(raw: ESAggregateProperties) {
  def this(str: String) = this(ESAggregateProperties(str, "not_analyzed"))
}
case class ESSuggestField(`type`: String = "string",
                          analyzer: String = "autocomplete",
                          search_analyzer: String = "standard",
                          include_in_all: Boolean = false,
                          store: Boolean = true) extends ESPropertyFields

case class ESAggregateProperties(`type`: String, index:String)


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
  * @param fieldAggregations the aggregation data to retrieve, results will differ based on the search criteria
  * @param maxAggregations the default is 10, this should only be specified if the use has requested to see more options
  * @param from used for pagination, where to start the returned results
  * @param size used for pagination, how many results to return
  */
case class LibrarySearchParams(
  searchString: Option[String],
  filters: Map[String, Seq[String]],
  fieldAggregations: Seq[String],
  maxAggregations: Option[Int],
  from: Int = 0,
  size: Int = 10)

object LibrarySearchParams {
  def apply(searchString: Option[String], filters: Map[String, Seq[String]], fieldAggregations: Seq[String], maxAggregations: Option[Int], from: Option[Int], size: Option[Int]) = {
    new LibrarySearchParams(searchString, filters, fieldAggregations, maxAggregations, from.getOrElse(0), size.getOrElse(10))
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


