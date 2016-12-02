package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.AttributeFormat
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

case class AttributeDefinition(properties: Map[String, AttributeDetail])

case class AttributeDetail(`type`: String, items: Option[AttributeDetail] = None)

case class ESDatasetProperty(properties: Map[String, ESDetail])

case class ESDetail(`type`: String)


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
case class LibrarySearchParams(
  searchTerm: Option[String],
  from: Int = 0,
  size: Int = 10)

object LibrarySearchParams {
  def apply(searchTerm: Option[String], from: Option[Int], size: Option[Int]) = {
    new LibrarySearchParams(searchTerm, from.getOrElse(0), size.getOrElse(10))
  }
}

case class LibrarySearchResponse(
  searchParams: LibrarySearchParams,
  total: Int,
  results: Seq[JsValue]) {
}


// classes to create the ES queries in json format
// {"query":{"match_all":{}}}"
// {"query":{"match":{"_all":"<str>"}}}"
trait QueryMap

case class ESQuery(query: QueryMap)

case class ESMatch(`match`: Map[String, String]) extends QueryMap {
  // when the search term should search all columns/attributes
  def this(value: String) = this(Map("_all" -> value))
}

case class ESMatchAll(match_all: Map[String,String]) extends QueryMap {
  def this() = this(Map.empty[String,String])
}


