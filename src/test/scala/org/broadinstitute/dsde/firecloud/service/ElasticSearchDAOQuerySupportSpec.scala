package org.broadinstitute.dsde.firecloud.service


import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{ElasticSearchDAOQuerySupport, ElasticSearchDAOSupport}
import org.broadinstitute.dsde.firecloud.model._
import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.{BoolQueryBuilder, QueryBuilder}
import org.scalatest.FreeSpec
import org.scalatest.Assertions._
import spray.json._

class ElasticSearchDAOQuerySupportSpec extends FreeSpec with ElasticSearchDAOQuerySupport {

  val indexname = "ElasticSearchSpec"

  val criteria = LibrarySearchParams(Some("searchString"),
    Map.empty[String, Seq[String]],
    Map.empty[String, Int],
    from = 0, size=10)

  // create an ElasticSearch client. Client requires legal urls for its servers argument, but those
  // urls don't have to point to an actual ES instance.
  val client: TransportClient = buildClient(FireCloudConfig.ElasticSearch.servers)

  "ElasticSearchDAOQuerySupport" - {
    "when createQuery is given a group for the current user" - {
      "should include group in search query" in {
        val baseRequest = buildSearchQuery(client, indexname, criteria, Seq("whitelistedgroup"))
        val jsonRequest = getSearchRequestAsJson(baseRequest)
        validateGroupTerm(jsonRequest, Some("whitelistedgroup"))
      }
    }
    "when createQuery is given no groups for the current user" - {
      "should not have groups in search query" in {
        val baseRequest = buildSearchQuery(client, indexname, criteria, Seq.empty[String])
        val jsonRequest = getSearchRequestAsJson(baseRequest)
        validateGroupTerm(jsonRequest, None)
      }
    }
    "when sorting by an explicit field and direction" - {
      "sort field and direction should be present in query" in {
        val sortField = Some("library:datasetName")
        val sortDirection = Some("asc")

        val sortCriteria = criteria.copy(sortField=sortField,sortDirection=sortDirection)
        val baseRequest = buildSearchQuery(client, indexname, sortCriteria, Seq.empty[String])
        val jsonRequest = getSearchRequestAsJson(baseRequest)
        validateSortField(jsonRequest, sortField)
        validateSortDirection(jsonRequest, sortDirection)
      }
    }
    "when sorting by an explicit field without direction" - {
      "sort field is present in query and sort direction is defaulted to asc" in {
        val sortField = Some("library:datasetName")

        val sortCriteria = criteria.copy(sortField=sortField,sortDirection=None)
        val baseRequest = buildSearchQuery(client, indexname, sortCriteria, Seq.empty[String])
        val jsonRequest = getSearchRequestAsJson(baseRequest)
        validateSortField(jsonRequest, sortField)
        validateSortDirection(jsonRequest, Some("asc"))
      }
    }
    "when sorting by an unknown direction" - {
      "sort field is present in query and sort direction is defaulted to asc" in {
        val sortField = Some("library:datasetName")

        val sortCriteria = criteria.copy(sortField=sortField,sortDirection=Some("unknown"))
        val baseRequest = buildSearchQuery(client, indexname, sortCriteria, Seq.empty[String])
        val jsonRequest = getSearchRequestAsJson(baseRequest)
        validateSortField(jsonRequest, sortField)
        validateSortDirection(jsonRequest, Some("asc"))
      }
    }
    "when specifying a sort order but no sort key" - {
      "neither sort order nor sort key is present in query" in {
        val sortCriteria = criteria.copy(sortField=None,sortDirection=Some("asc"))
        val baseRequest = buildSearchQuery(client, indexname, sortCriteria, Seq.empty[String])
        val jsonRequest = getSearchRequestAsJson(baseRequest)
        validateSortField(jsonRequest, None)
        validateSortDirection(jsonRequest, None)
      }
    }
    "when specifying neither sort order nor sort key" - {
      "neither sort order nor sort key is present in query" in {
        val sortCriteria = criteria.copy(sortField=None,sortDirection=None)
        val baseRequest = buildSearchQuery(client, indexname, sortCriteria, Seq.empty[String])
        val jsonRequest = getSearchRequestAsJson(baseRequest)
        validateSortField(jsonRequest, None)
        validateSortDirection(jsonRequest, None)
      }
    }
  }

  // TODO: does text search criteria properly search against _all?
  // TODO: does empty text search do the right thing?
  // TODO: do facet selections properly become term filters?
  // TODO: do facet requests properly become aggregations?
  // TODO: does an expanded facet properly expand?
  // TODO: does pagination work?
  // TODO: does page size work?



  def getSearchRequestAsJson(baseQuery:SearchRequestBuilder): JsObject = {
    baseQuery.internalBuilder().buildAsBytes(XContentType.JSON).toUtf8.parseJson.asJsObject
  }
  def getFromValue(json:JsObject): Option[Int] = {
    json.fields.get("from") match {
      case Some(x:JsNumber) => Some(x.value.toInt)
      case _ => None
    }
  }
  def getSizeValue(json:JsObject): Option[Int] = {
    json.fields.get("size") match {
      case Some(x:JsNumber) => Some(x.value.toInt)
      case _ => None
    }
  }
  def getSortField(json:JsObject): Option[String] = {
    getSortObject(json) match {
      case Some(sortObj:JsObject) =>
        assertResult(1) {sortObj.fields.size}
        Some(sortObj.fields.keys.head)
      case _ => None
    }
  }
  def getSortOrder(json:JsObject): Option[String] = {
    getSortObject(json) match {
      case Some(sortObj:JsObject) =>
        assertResult(1) {sortObj.fields.size}
        sortObj.fields.values.head.asJsObject.fields.get("order") match {
          case Some(x:JsString) => Some(x.value)
          case _ => None
        }
      case _ => None
    }
  }
  def getSortObject(json:JsObject): Option[JsObject] = {
    json.fields.get("sort") match {
      case Some(arr:JsArray) =>
        assertResult(1) {arr.elements.size} // app code only support sorting on a single field for now
        Some(arr.elements.head.asJsObject)
      case _ => None
    }
  }

  def getQuery(json:JsObject): Option[JsValue] = {
    json.fields.get("query")
  }

  def validateSortField(json:JsObject, expectedSortField:Option[String]): Unit = {
    // the ES DAO actually sorts on the inner field with a suffix of ".sort", so add that here.
    expectedSortField match {
      case Some(x) => assertResult(Some(x + ".sort")) {getSortField(json)}
      case None => assertResult(None) {getSortField(json)}
    }
  }

  def validateSortDirection(json:JsObject, expectedSortDirection:Option[String]): Unit = {
    assertResult(expectedSortDirection) {getSortOrder(json)}
  }

  def validateGroupTerm(json:JsObject, expectedGroup:Option[String]) = {
    getQuery(json) match {
      case Some(a:JsObject) =>
        assertResult(Set("bool"), "query should be an outer bool clause") {a.fields.keySet}
        val outerbool = a.fields("bool").asJsObject
        assertResult(Set("must"), "outer bool clause should be a must clause") {outerbool.fields.keySet}
        val must = outerbool.fields("must")
        must match {
          case arr:JsArray =>
            assertResult(2, "must clause should have two elements") {arr.elements.size}
            val matchClause = arr.elements.head.asJsObject
            assertResult(Set("match"), "first element of must clause should be a match") {matchClause.fields.keySet}
            val groupBoolClause = arr.elements.tail.head.asJsObject
            assertResult(Set("bool"), "second (and last) element of must clause should be a bool clause") {groupBoolClause.fields.keySet}
            val groupbool = groupBoolClause.fields("bool")
            expectedGroup match {
              case Some(group) =>
                assertResult(expectedDiscoverableGroup(group), "group criteria should include expected group name") {groupbool}
              case None =>
                assertResult(expectedNoDiscoverableGroups, "group criteria should be just the must-not-exists") {groupbool}
            }
          case _ => fail("must clause should be a JsArray")
        }

      case _ => fail("query was not a JsObject")
    }
  }

  private val expectedNoDiscoverableGroups = """{
                                               |  "should":{
                                               |		"bool":{
                                               |		   "must_not":{
                                               |			  "exists":{
                                               |				 "field":"_discoverableByGroups"
                                               |			  }
                                               |		   }
                                               |		}
                                               |	 }
                                               |}""".stripMargin.parseJson

  private def expectedDiscoverableGroup(group:String):JsValue = {
    s"""{
      |  "should":[
      |	 {
      |		"bool":{
      |		   "must_not":{
      |			  "exists":{
      |				 "field":"_discoverableByGroups"
      |			  }
      |		   }
      |		}
      |	 },
      |	 {
      |		"terms":{
      |		   "_discoverableByGroups":[
      |			  "$group"
      |		   ]
      |		}
      |	 }
      |  ]
      |}""".stripMargin.parseJson
  }


}
