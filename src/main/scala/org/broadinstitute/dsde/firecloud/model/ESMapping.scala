package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol.AttributeFormat
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

case class AttributeDefinition(properties: Map[String, AttributeDetail])
case class AttributeDetail(`type`: String, items: Option[AttributeDetail]=None)

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
                                from: Option[Int],
                                size: Option[Int]) {
}

case class LibrarySearchResponse(
                                  searchTerm: Option[String],
                                  from: Int,
                                  size: Int,
                                  total: Int,
                                  results: Array[JsValue]) {
  def this (params: LibrarySearchParams, total: Int, results: Array[JsValue]) =
    this(params.searchTerm, params.from.getOrElse(0), params.size.getOrElse(10), total, results)
}


// classes to create the following 2 query formats for ES
//  ESmatchAll = "{ \"query\": { \"match_all\" : {}}}"
//  ESwildcardSearch = "{ \"query\": { \"wildcard\" : { \"_all\" : \"*%s*\"}}}"


case class ESMatchAll(match_all: Map[String, String]) { //extends QueryMap {
  def this() = this(Map.empty)
}


case class ESWildcard(wildcard: ESWildcardSearchTerm) { //extends QueryMap {
}

object ESWildcardObj {
  def apply (term: String) :ESWildcard = ESWildcard(ESWildcardSearchTerm("*"+term+"*"))
}

case class ESQuery(query: Either[ESMatchAll,ESWildcard]) {
}


case class ESWildcardSearchTerm(_all: String) {
}


