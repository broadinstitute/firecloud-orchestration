package org.broadinstitute.dsde.firecloud.service


import org.broadinstitute.dsde.firecloud.FireCloudConfig
import org.broadinstitute.dsde.firecloud.dataaccess.{ElasticSearchDAOQuerySupport, ElasticSearchDAOSupport}
import org.broadinstitute.dsde.firecloud.model._
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
        val baseQuery:QueryBuilder = createQuery(criteria, Seq("whitelistedgroup"))
        baseQuery match {
          case bqb:BoolQueryBuilder =>
            assert(bqb.hasClauses)
            val jsonQuery = bqb.buildAsBytes(XContentType.JSON).toUtf8
            validateGroupTerm(jsonQuery, Some("whitelistedgroup"))
          case _ => fail("query is not of type BoolQueryBuilder")
        }
      }
    }
    "when createQuery is given no groups for the current user" - {
      "should not have groups in search query" in {
        val baseQuery:QueryBuilder = createQuery(criteria, Seq.empty[String])
        baseQuery match {
          case bqb:BoolQueryBuilder =>
            assert(bqb.hasClauses)
            val jsonQuery = bqb.buildAsBytes(XContentType.JSON).toUtf8
            validateGroupTerm(jsonQuery, None)
          case _ => fail("query is not of type BoolQueryBuilder")
        }
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



  def validateGroupTerm(jsonQuery:String, expectedGroup:Option[String]) = {
    val json:JsValue = jsonQuery.parseJson
    json match {
      case a:JsObject =>
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
