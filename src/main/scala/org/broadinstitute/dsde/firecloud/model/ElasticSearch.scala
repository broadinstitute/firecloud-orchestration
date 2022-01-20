package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.DataUse.ResearchPurpose
import org.broadinstitute.dsde.rawls.model.{AttributeFormat, PlainArrayAttributeListSerializer}
import org.broadinstitute.dsde.rawls.model.WorkspaceJsonSupport.AttributeNameFormat
import org.broadinstitute.dsde.rawls.model.Attributable.AttributeMap
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue}

object ElasticSearch {
  final val fieldAll = "_all"
  final val fieldSuggest = "_suggest"
  final val fieldDiscoverableByGroups = "_discoverableByGroups"
  final val fieldWorkspaceId = "workspaceId"
  final val fieldOntologyParents = "parents"
  final val fieldOntologyParentsOrder = "order"
  final val fieldOntologyParentsLabel = "label"
}

case class AttributeDefinition(properties: Map[String, AttributeDetail])

case class AttributeDetail(
  `type`: String,
  items: Option[AttributeDetail] = None,
  aggregate: Option[JsObject] = None,
  indexable: Option[Boolean] = None,
  typeahead: Option[String] = None
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
  // https://www.elastic.co/guide/en/elasticsearch/reference/2.4/search-suggesters-completion.html
  def completionField = ESInnerField(
    "completion",
    analyzer = Option("simple"),
    search_analyzer = Option("simple")
  )
  def keywordField(`type`:String) = ESInnerField("keyword")
  def sortField(`type`:String) = ESInnerField(`type`,
    analyzer = Some("sort_analyzer"),
    include_in_all = Some(false),
    fielddata = Some(true)
  )
}

// top-level field defs, for facet and non-facet types
case class ESType(`type`: String, fields: Option[Map[String,ESInnerField]], copy_to: Option[String] = None ) extends ESPropertyFields
object ESType extends ESPropertyFields {
  def apply(`type`: String, hasPopulateSuggest: Boolean, hasSearchSuggest: Boolean, isAggregatable: Boolean):ESType =  {
    val innerFields = Map.empty[String,ESInnerField] ++
      (if (`type`.equals("string"))
        Map("sort" -> sortField(`type`))
      else
        Map("sort" -> ESInnerField(`type`))) ++
      (if (isAggregatable) Map("keyword" -> keywordField(`type`)) else Nil) ++
      (if (hasPopulateSuggest) Map("suggestKeyword" -> keywordField(`type`)) else Nil)
    if (hasSearchSuggest)
      new ESType(`type`, Option(innerFields), Option(ElasticSearch.fieldSuggest))
    else
      new ESType(`type`, Option(innerFields))
  }

}

case class ESNestedType(properties:Map[String,ESInnerField], `type`:String="nested") extends ESPropertyFields

case class ESInternalType(
  `type`: String,
  index: String = "not_analyzed",
  include_in_all: Boolean = false) extends ESPropertyFields

// def for ElasticSearch's multi-fields: https://www.elastic.co/guide/en/elasticsearch/reference/2.4/multi-fields.html
// technically, the top-level fields and inner fields are the same thing, and we *could* use the same class.
// we keep them different here for ease of development and clarity of code-reading.
case class ESInnerField(`type`: String,
                        analyzer: Option[String] = None,
                        search_analyzer: Option[String] = None,
                        include_in_all: Option[Boolean] = None,
                        store: Option[Boolean] = None,
                        copy_to: Option[String] = None,
                        fielddata: Option[Boolean] = None) extends ESPropertyFields

// classes for sending documents to ES to be indexed
trait Indexable {
  def id: String

  def content: JsObject
}

case class Document(val id: String, val content: JsObject) extends Indexable

object Document {
  def apply(id: String, valMap: AttributeMap) = {
    implicit val impAttributeFormat = new AttributeFormat with PlainArrayAttributeListSerializer
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
  researchPurpose: Option[ResearchPurpose],
  fieldAggregations: Map[String, Int],
  from: Int = 0,
  size: Int = 10,
  sortField: Option[String] = None,
  sortDirection: Option[String] = None)

object LibrarySearchParams {
  def apply(searchString: Option[String], filters: Map[String, Seq[String]], researchPurpose: Option[ResearchPurpose], fieldAggregations: Map[String, Int], from: Option[Int], size: Option[Int], sortField: Option[String], sortDirection: Option[String]) = {
    new LibrarySearchParams(searchString, filters, researchPurpose, fieldAggregations, from.getOrElse(0), size.getOrElse(10), sortField, sortDirection)
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

case class LibraryBulkIndexResponse(totalCount: Int, hasFailures: Boolean, failureMessages: Map[String,String])


