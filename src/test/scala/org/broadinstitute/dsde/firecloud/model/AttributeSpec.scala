package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.FireCloudException
import org.broadinstitute.dsde.firecloud.model.Attributable.AttributeMap
import org.scalatest.{Assertions, FreeSpec}
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._


class AttributeSpec extends FreeSpec with Assertions {

  "AttributeName model" - {
    "when unmarshalling from json" - {
      "should handle namespaces" in {
        val testData = """ "namespace:name" """
        val an = testData.parseJson.convertTo[AttributeName]
        assertResult("namespace") {an.namespace}
        assertResult("name") {an.name}
      }
      "should handle missing namespace" in {
        val testData = """ "name" """
        val an = testData.parseJson.convertTo[AttributeName]
        assertResult(AttributeName.defaultNamespace) {an.namespace}
        assertResult("name") {an.name}
      }
      "should fail with multiple delimiters" in {
        val testData = """ "one:two:three" """
        intercept[FireCloudException] {
          testData.parseJson.convertTo[AttributeName]
        }
      }
    }
    "when marshalling to json" - {
      "should handle arbitrary namespace" in {
        val testData = AttributeName("namespace", "name")
        assertResult(JsString("namespace:name")) {testData.toJson}
      }
      "should handle library namespace" in {
        val testData = AttributeName(AttributeName.libraryNamespace, "name")
        assertResult(JsString("library:name")) {testData.toJson}
      }
      "should omit default namespace" in {
        val testData = AttributeName.withDefaultNS("name")
        assertResult(JsString("name")) {testData.toJson}
      }
    }
  }

  "AttributeMap model" - {
    "when unmarshalling from json" - {
      "should handle string value" in {
        val testData =
          """
            | {"testKey" : "helloWorld"}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        assertResult(AttributeString("helloWorld")) { attr }
      }
      "should handle numeric value" in {
        val testData =
          """
            | {"testKey" : 123}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        assertResult(AttributeNumber(123)) { attr }
      }
      "should handle boolean value" in {
        val testData =
          """
            | {"testKey" : true}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        assertResult(AttributeBoolean(true)) { attr }
      }
      "should handle null value" in {
        val testData =
          """
            | {"testKey" : null}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        assertResult(AttributeNull) { attr }
      }
      "should handle entity reference value" in {
        val testData =
          """
            | {"testKey" : {
            |   "entityType" : "sample",
            |   "entityName" : "My Sample Name"
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        assertResult(AttributeEntityReference("sample", "My Sample Name")) { attr }
      }
      "should handle empty value list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "AttributeValue",
            |   "items" : []
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        assertResult(AttributeValueEmptyList) { attr }
      }
      "should handle empty entity reference list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "EntityReference",
            |   "items" : []
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        assertResult(AttributeEntityReferenceEmptyList) { attr }
      }
      "should handle populated entity reference list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "EntityReference",
            |   "items" : [
            |     {"entityType" : "sample", "entityName" : "My Sample Name"},
            |     {"entityType" : "participant", "entityName" : "My Participant Name"},
            |     {"entityType" : "pair", "entityName" : "My Pair Name"}
            |   ]
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        val expected = AttributeEntityReferenceList( Seq(
          AttributeEntityReference("sample", "My Sample Name"),
          AttributeEntityReference("participant", "My Participant Name"),
          AttributeEntityReference("pair", "My Pair Name")
        ) )
        assertResult(expected) { attr }
      }
      "should handle populated string list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "AttributeValue",
            |   "items" : [ "foo", "bar", "baz" ]
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        val expected = AttributeValueList(Seq(
          AttributeString("foo"),
          AttributeString("bar"),
          AttributeString("baz")
        ))
        assertResult(expected) { attr }
      }
      "should handle populated numeric list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "AttributeValue",
            |   "items" : [ 123, 456, 789 ]
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        val expected = AttributeValueList(Seq(
          AttributeNumber(123),
          AttributeNumber(456),
          AttributeNumber(789)
        ))
        assertResult(expected) { attr }
      }
      "should handle populated boolean list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "AttributeValue",
            |   "items" : [ true, false, true ]
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        val expected = AttributeValueList(Seq(
          AttributeBoolean(true),
          AttributeBoolean(false),
          AttributeBoolean(true)
        ))
        assertResult(expected) { attr }
      }
      "should fail if entity reference list contains values" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "EntityReference",
            |   "items" : [ 123, 456, 789 ]
            | }}
          """.stripMargin
        intercept[DeserializationException] {
          testData.parseJson.convertTo[AttributeMap]
        }
      }
      "should fail if value list contains entities" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "AttributeValue",
            |   "items" : [
            |     {"entityType" : "sample", "entityName" : "My Sample Name"},
            |     {"entityType" : "participant", "entityName" : "My Participant Name"},
            |     {"entityType" : "pair", "entityName" : "My Pair Name"}
            |   ]
            | }}
          """.stripMargin
        intercept[DeserializationException] {
          testData.parseJson.convertTo[AttributeMap]
        }
      }
      "should work even if value list contains mixed types" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "AttributeValue",
            |   "items" : ["hello world", 123, false]
            | }}
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]
        val attr = obj.getOrElse(AttributeName.withDefaultNS("testKey"), fail("attr doesn't exist"))
        val expected = AttributeValueList(Seq(
          AttributeString("hello world"),
          AttributeNumber(123),
          AttributeBoolean(false)
        ))
        assertResult(expected) { attr }
      }
      "should fail if list specifies unknown type" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "SomeValueNotExpected",
            |   "items" : ["foo", "bar", "baz"]
            | }}
          """.stripMargin
        intercept[DeserializationException] {
          testData.parseJson.convertTo[AttributeMap]
        }
      }
      "should fail if list omits type" in {
        val testData =
          """
            | {"testKey" : {
            |   "items" : ["foo", "bar", "baz"]
            | }}
          """.stripMargin
        intercept[DeserializationException] {
          testData.parseJson.convertTo[AttributeMap]
        }
      }
      "should fail if entity reference list contains list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "EntityReference",
            |   "items" : [
            |     {"anotherList" :
            |       {
            |         "itemsType" : "AttributeValue",
            |         "items" : ["foo", "bar", "baz"]
            |       }
            |     }
            |   ]
            | }}
          """.stripMargin
        intercept[DeserializationException] {
          testData.parseJson.convertTo[AttributeMap]
        }
      }
      "should fail if value list contains list" in {
        val testData =
          """
            | {"testKey" : {
            |   "itemsType" : "AttributeValue",
            |   "items" : [
            |     {"anotherList" :
            |       {
            |         "itemsType" : "AttributeValue",
            |         "items" : ["foo", "bar", "baz"]
            |       }
            |     }
            |   ]
            | }}
          """.stripMargin
        intercept[DeserializationException] {
          testData.parseJson.convertTo[AttributeMap]
        }
      }
      "should handle a json with every possible type" in {
        val testData =
          """
            | {
            |   "stringKey" : "stringValue",
            |   "numberKey" : 123,
            |   "booleanKey" : true,
            |   "nullKey" : null,
            |   "entityReferenceKey" : {"entityType" : "sample", "entityName" : "Top-level reference"},
            |   "emptyValueListKey" : { "itemsType" : "AttributeValue", "items" : []},
            |   "emptyEntityReferenceListKey" : { "itemsType" : "EntityReference", "items" : []},
            |   "valueListKey" : { "itemsType" : "AttributeValue", "items" : ["foo", 456, false]},
            |   "entityReferenceListKey" : {
            |     "itemsType" : "EntityReference",
            |     "items" : [
            |       {"entityType" : "sample", "entityName" : "My Sample Name"},
            |       {"entityType" : "participant", "entityName" : "My Participant Name"},
            |       {"entityType" : "pair", "entityName" : "My Pair Name"}
            |     ]
            |   }
            | }
          """.stripMargin
        val obj = testData.parseJson.convertTo[AttributeMap]

        val expected = Map[AttributeName,Attribute](
          AttributeName.withDefaultNS("stringKey") -> AttributeString("stringValue"),
          AttributeName.withDefaultNS("numberKey") -> AttributeNumber(123),
          AttributeName.withDefaultNS("booleanKey") -> AttributeBoolean(true),
          AttributeName.withDefaultNS("nullKey") -> AttributeNull,
          AttributeName.withDefaultNS("entityReferenceKey") -> AttributeEntityReference("sample", "Top-level reference"),
          AttributeName.withDefaultNS("emptyValueListKey") -> AttributeValueEmptyList,
          AttributeName.withDefaultNS("emptyEntityReferenceListKey") -> AttributeEntityReferenceEmptyList,
          AttributeName.withDefaultNS("valueListKey") -> AttributeValueList(Seq(
            AttributeString("foo"), AttributeNumber(456), AttributeBoolean(false)
          )),
          AttributeName.withDefaultNS("entityReferenceListKey") -> AttributeEntityReferenceList(Seq(
            AttributeEntityReference("sample", "My Sample Name"),
            AttributeEntityReference("participant", "My Participant Name"),
            AttributeEntityReference("pair", "My Pair Name")
          ))
        )
        assertResult(expected) { obj }
      }
    }
    "when marshalling to json" - {
      "should handle string value" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeString("abc"))
        val expected =
          """
            |{"ns:name":"abc"}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle numeric value" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeNumber(777))
        val expected =
          """
            |{"ns:name":777}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle boolean value" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeBoolean(true))
        val expected =
          """
            |{"ns:name":true}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle null value" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeNull)
        val expected =
          """
            |{"ns:name":null}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle entity reference value" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->
          AttributeEntityReference("sample", "some sample name"))
        val expected =
          """
            |{"ns:name":{"entityType":"sample","entityName":"some sample name"}}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle empty value list" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeValueEmptyList)
        val expected =
          """
            |{"ns:name":{"itemsType":"AttributeValue","items":[]}}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle empty entity reference list" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeEntityReferenceEmptyList)
        val expected =
          """
            |{"ns:name":{"itemsType":"EntityReference","items":[]}}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle populated entity reference list" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeEntityReferenceList(Seq(
          AttributeEntityReference("sample", "some sample name"),
          AttributeEntityReference("participant", "some participant name")
        )))
        val expected =
          """
            |{"ns:name":{"itemsType":"EntityReference","items":[{"entityType":"sample","entityName":"some sample name"},{"entityType":"participant","entityName":"some participant name"}]}}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle populated mixed value list" in {
        val testData:AttributeMap = Map(AttributeName("ns","name")->AttributeValueList(Seq(
          AttributeString("def"),
          AttributeNumber(999),
          AttributeBoolean(true),
          AttributeNull
        )))
        val expected =
          """
            |{"ns:name":{"itemsType":"AttributeValue","items":["def",999,true,null]}}
          """.stripMargin.trim
        assertResult(expected) { testData.toJson.compactPrint }
      }
      "should handle a json with every possible type" in {
        val testData:AttributeMap = Map[AttributeName,Attribute](
          AttributeName.withDefaultNS("stringKey") -> AttributeString("stringValue"),
          AttributeName.withDefaultNS("numberKey") -> AttributeNumber(123),
          AttributeName.withDefaultNS("booleanKey") -> AttributeBoolean(true),
          AttributeName.withDefaultNS("nullKey") -> AttributeNull,
          AttributeName.withDefaultNS("entityReferenceKey") -> AttributeEntityReference("sample", "TopLevelReference"),
          AttributeName.withDefaultNS("emptyValueListKey") -> AttributeValueEmptyList,
          AttributeName.withDefaultNS("emptyEntityReferenceListKey") -> AttributeEntityReferenceEmptyList,
          AttributeName.withDefaultNS("valueListKey") -> AttributeValueList(Seq(
            AttributeString("foo"), AttributeNumber(456), AttributeBoolean(false)
          )),
          AttributeName.withDefaultNS("entityReferenceListKey") -> AttributeEntityReferenceList(Seq(
            AttributeEntityReference("sample", "MySampleName"),
            AttributeEntityReference("participant", "MyParticipantName"),
            AttributeEntityReference("pair", "MyPairName")
          ))
        )
        // use replace to make the test code easier to read
        val expected =
          """
            |{
            |  "stringKey" : "stringValue",
            |  "numberKey" : 123,
            |  "booleanKey" : true,
            |  "nullKey" : null,
            |  "entityReferenceKey" : {"entityType":"sample","entityName":"TopLevelReference"},
            |  "emptyValueListKey" : {"itemsType" : "AttributeValue", "items" : []},
            |  "emptyEntityReferenceListKey" : {"itemsType" : "EntityReference", "items" : []},
            |  "valueListKey" : {"itemsType" : "AttributeValue", "items" : [ "foo", 456, false ]},
            |  "entityReferenceListKey" : {"itemsType" : "EntityReference", "items" : [
            |    {"entityType":"sample","entityName":"MySampleName"},
            |    {"entityType":"participant","entityName":"MyParticipantName"},
            |    {"entityType":"pair","entityName":"MyPairName"}
            |  ]}
            |}
          """.stripMargin.trim.replace(" ", "").replace("\n","").replace("\r","")

        val actual = testData.toJson.compactPrint
        // we can't test the two strings directly, because AttributeMap doesn't preserve order,
        // and therefore we don't know in which order the keys will appear. So, jump through a
        // few hoops to validate.
        assertResult(expected.length) { actual.length }
        // strip off the first and last { and }, which gives us each individual key/value pair,
        // sort, and compare the sorted results
        val expectedSubStrings = expected.substring(1,expected.length-1).split(",").sorted
        val actualSubStrings = actual.substring(1,actual.length-1).split(",").sorted
        assertResult(expectedSubStrings) { actualSubStrings }
      }
    }
  }

}


