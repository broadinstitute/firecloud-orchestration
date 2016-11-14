package org.broadinstitute.dsde.firecloud.model

case class AttributeDefinition(properties: Map[String, AttributeDetail])
case class AttributeDetail(`type`: String, items: Option[AttributeDetail]=None)

case class ESDatasetProperty(properties: Map[String, ESDetail])
case class ESDetail(`type`: String)


//  ESmatchAll = "{ \"query\": { \"match_all\" : {}}}"
//  ESwildcardSearch = "{ \"query\": { \"wildcard\" : { \"_all\" : \"*%s*\"}}}"

case class ESAllQuery(query: ESMatchAll) {
}

//sealed trait QueryMap
case class ESMatchAll(match_all: Map[String, String]) { //extends QueryMap {
  def this() = this(Map.empty)
}


//object ESMatchAllObj {
//  def apply () :ESMatchAll = ESMatchAll(Map.empty)
//}

case class ESSearchQuery(query: ESWildcard) {
  def this(term: String) = this(ESWildcardObj(term))
}

case class ESWildcard(wildcard: ESWildcardSearchTerm) { //extends QueryMap {
}

object ESWildcardObj {
  def apply (term: String) :ESWildcard = ESWildcard(ESWildcardSearchTerm("*"+term+"*"))
}

case class ESWildcardSearchTerm(_all: String) {
}

