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
case class ESType(`type`: String) extends ESPropertyFields
case class ESAggregatableType(`type`: String, fields: ESRaw) extends ESPropertyFields {
  def this(str: String) = this(str, new ESRaw(str))
}
case class ESRaw(raw: ESAggregateProperties) {
  def this(str: String) = this(ESAggregateProperties(str, "not_analyzed"))
}

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


