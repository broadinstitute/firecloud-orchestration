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
    None,
    Map.empty[String, Int],
    from = 0, size=10)

  // create an ElasticSearch client. Client requires legal urls for its servers argument, but those
  // urls don't have to point to an actual ES instance.
  val client: TransportClient = buildClient(FireCloudConfig.ElasticSearch.servers, FireCloudConfig.ElasticSearch.clusterName)

  "ElasticSearchDAOQuerySupport" - {

    "discoverability" - {
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
    }

    "sorting" - {
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

    "pagination" - {
      "when specifying a page offset" - {
        "page offset is present in query" in {
          val offset = 23
          val searchCriteria = criteria.copy(from=offset)
          val baseRequest = buildSearchQuery(client, indexname, searchCriteria, Seq.empty[String])
          val jsonRequest = getSearchRequestAsJson(baseRequest)
          assertResult(Some(offset)) {getFromValue(jsonRequest)}
          assertResult(Some(10)) {getSizeValue(jsonRequest)}
        }
      }
      "when omitting a page offset" - {
        "page offset defaults to 0" in {
          val baseRequest = buildSearchQuery(client, indexname, criteria, Seq.empty[String])
          val jsonRequest = getSearchRequestAsJson(baseRequest)
          assertResult(Some(0)) {getFromValue(jsonRequest)}
          assertResult(Some(10)) {getSizeValue(jsonRequest)}
        }
      }
      "when specifying a page size" - {
        "page size is present in query" in {
          val pageSize = 46
          val searchCriteria = criteria.copy(size=pageSize)
          val baseRequest = buildSearchQuery(client, indexname, searchCriteria, Seq.empty[String])
          val jsonRequest = getSearchRequestAsJson(baseRequest)
          assertResult(Some(0)) {getFromValue(jsonRequest)}
          assertResult(Some(pageSize)) {getSizeValue(jsonRequest)}
        }
      }
      "when omitting a page size" - {
        "page size defaults to 10" in {
          val baseRequest = buildSearchQuery(client, indexname, criteria, Seq.empty[String])
          val jsonRequest = getSearchRequestAsJson(baseRequest)
          assertResult(Some(0)) {getFromValue(jsonRequest)}
          assertResult(Some(10)) {getSizeValue(jsonRequest)}
        }
      }
      "when specifying both page offset and page size" - {
        "both page offset and page size are present in query" in {
          val offset = 23
          val pageSize = 46
          val searchCriteria = criteria.copy(from=offset,size=pageSize)
          val baseRequest = buildSearchQuery(client, indexname, searchCriteria, Seq.empty[String])
          val jsonRequest = getSearchRequestAsJson(baseRequest)
          assertResult(Some(offset)) {getFromValue(jsonRequest)}
          assertResult(Some(pageSize)) {getSizeValue(jsonRequest)}
        }
      }
    }

    "text search" - {
      "when specifying text search" - {
        "user criteria is present, searching against _all" in {
          val searchTerm = "normcore kitsch mustache bespoke semiotics"
          val searchCriteria = criteria.copy(searchString=Some(searchTerm))
          val baseRequest = buildSearchQuery(client, indexname, searchCriteria, Seq.empty[String])
          val jsonRequest = getSearchRequestAsJson(baseRequest)
          validateSearchTermAll(jsonRequest, searchTerm)
        }
      }
      "when omitting text search" - {
        "no search is present in query" in {
          val searchCriteria = criteria.copy(searchString=None)
          val baseRequest = buildSearchQuery(client, indexname, searchCriteria, Seq.empty[String])
          val jsonRequest = getSearchRequestAsJson(baseRequest)
          // when omitting search term, we have an empty "match_all" and the "bool" for discover mode
          val arr = getMustArray(jsonRequest)
          val matchAllClause = arr.elements.head.asJsObject
          assertResult(Set("match_all"), "first element of must clause should be a match") {matchAllClause.fields.keySet}
          assertResult(JsObject(("boost",JsNumber(1.0)))) {matchAllClause.fields("match_all").asJsObject}
          // calling getMustBoolObject will validate it down to that level
          getMustBoolObject(jsonRequest)
        }
      }
    }

  }

  // TODO: do facet selections properly become term filters?
  // TODO: do facet requests properly become aggregations?
  // TODO: does an expanded facet properly expand?

  def getSearchRequestAsJson(baseQuery:SearchRequestBuilder): JsObject = {
    baseQuery.toString.parseJson.asJsObject
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
    val groupBoolClause = getMustBoolObject(json)
    val groupbool = groupBoolClause.fields("bool").asJsObject
    expectedGroup match {
      case Some(group) =>
        assertDiscoverableGroups(groupbool, Some(group))
        // assertResult(expectedDiscoverableGroup(group), "group criteria should include expected group name") {groupbool}
      case None =>
        assertDiscoverableGroups(groupbool, None)
        // assertResult(expectedNoDiscoverableGroups, "group criteria should be just the must-not-exists") {groupbool}
    }
  }

  def validateSearchTermAll(json:JsObject, expectedTerm:String) = {
    validateSearchTerm(json, expectedTerm, "_all")
  }

  def validateSearchTerm(json:JsObject, expectedTerm:String, expectedField:String) = {
    val shouldArray = getTextSearchShouldArray(json)
    assertResult(2) {shouldArray.elements.size}

    val allSearchMatch = shouldArray.elements.head.asJsObject
    validateSearchCriteria(allSearchMatch, expectedTerm, expectedField, "2<67%")

    val parentSearchNested = shouldArray.elements.tail.head.asJsObject
    assertResult(Set("nested"), s"search on parents should be a nested query") {parentSearchNested.fields.keySet}
    val parentSearchNestedQuery = parentSearchNested.fields("nested").asJsObject
    assert(parentSearchNestedQuery.fields.keySet.contains("query"), "nested parents query should contain a query")
    assert(parentSearchNestedQuery.fields.keySet.contains("path"), "nested parents query should  contain a path")
    assertResult("parents", "nested parents query should have a path of 'parents'") {parentSearchNestedQuery.fields("path").asInstanceOf[JsString].value}
    val parentSearchMatch = parentSearchNestedQuery.fields("query").asJsObject
    validateSearchCriteria(parentSearchMatch, expectedTerm, "parents.label", "3<75%")
  }

  private def validateSearchCriteria(json:JsObject, expectedTerm:String, expectedField:String, expectedMinMatch:String) = {

    assertResult(Set("match"), s"search on $expectedField should be a match clause") {json.fields.keySet}
    val search = json.fields("match").asJsObject
    assertResult(Set(expectedField), s"search clause should execute against only $expectedField") {search.fields.keySet}
    val searchCriteria = search.fields(expectedField).asJsObject
    assert(searchCriteria.fields.keySet.contains("query"), s"search criteria should contain 'query'")
    assert(searchCriteria.fields.keySet.contains("minimum_should_match"), s"search criteria should contain 'minimum_should_match'")
    assertResult(expectedTerm) {searchCriteria.fields("query").asInstanceOf[JsString].value}
    assertResult(expectedMinMatch) {searchCriteria.fields("minimum_should_match").asInstanceOf[JsString].value}
  }

  private def getMustArray(json:JsObject):JsArray = {
    getQuery(json) match {
      case Some(a:JsObject) =>
        assertResult(Set("bool"), "query should be an outer bool clause") {a.fields.keySet}
        val outerbool = a.fields("bool").asJsObject
        assert(outerbool.fields.keySet.contains("must"), "outer bool clause should include a must clause")
        val must = outerbool.fields("must")
        must match {
          case arr:JsArray =>
            assertResult(2, "must clause should have two elements") {arr.elements.size}
            arr
          case _ => fail("must clause should be a JsArray")
        }
      case _ => fail("query was not a JsObject")
    }
  }

  private def getTextSearchShouldArray(json:JsObject):JsArray = {
    val arr = getMustArray(json)
    val searchClause = arr.elements.head.asJsObject
    assertResult(Set("bool"), "first element of text search clause should be a bool") {searchClause.fields.keySet}
    val boolClause = searchClause.fields("bool").asJsObject
    assert(boolClause.fields.keySet.contains("should"), "first element of text search bool clause should inculde a should")
    val shouldArray = boolClause.fields("should") match {
      case arr:JsArray => arr
      case _ => fail("text search should clause should be an array")
    }
    shouldArray
  }

  private def getMustBoolObject(json:JsObject):JsObject = {
    val arr = getMustArray(json)
    val groupBoolClause = arr.elements.tail.head.asJsObject
    assertResult(Set("bool"), "second (and last) element of must clause should be a bool clause") {groupBoolClause.fields.keySet}
    groupBoolClause
  }

  private def assertDiscoverableGroups(json:JsObject, expectedGroup: Option[String]) = {
    val should = json.fields.get("should")
    should match {
      case Some(ja:JsArray) =>
        val expectedLength = if (expectedGroup.isEmpty) 1 else 2
        assertResult(expectedLength, s"should clause should have $expectedLength item(s)") {ja.elements.length}
        // don't bother asserting the types and keys below; will throw exception and fail test if
        // there's a problem.
        val mustNotField = ja.elements(0).asJsObject
          .fields("bool").asJsObject
            .fields("must_not").asInstanceOf[JsArray].elements(0).asJsObject
              .fields("exists").asJsObject
                .fields("field")
        assertResult(ElasticSearch.fieldDiscoverableByGroups) {mustNotField.asInstanceOf[JsString].value}
        expectedGroup foreach { grp =>
          val actualGroups = ja.elements(1).asJsObject
            .fields("terms").asJsObject
                .fields(ElasticSearch.fieldDiscoverableByGroups)
          assertResult(Set(JsString(grp))) {actualGroups.asInstanceOf[JsArray].elements.toSet}
        }
      case _ => fail("should clause should exist and be a JsArray")
    }
  }


}
