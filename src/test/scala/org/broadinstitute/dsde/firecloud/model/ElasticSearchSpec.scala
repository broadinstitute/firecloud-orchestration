package org.broadinstitute.dsde.firecloud.model

import org.scalatest.{Assertions, FreeSpec}
import spray.json.JsString
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

/**
  * Created by ahaessly on 1/19/17.
  */
class ElasticSearchSpec  extends FreeSpec with Assertions {

  "LibrarySearchParams model" - {
    "when unmarshalling from json" - {
      "should handle filters" in {
        val testData = """ {"filters": {"library:datatype":["cancer"]},"fieldAggregations":{"library:indication":5}} """
        val item = testData.parseJson.convertTo[LibrarySearchParams]
        assertResult(Seq("cancer")) {item.filters.getOrElse("library:datatype", Seq.empty)}
      }
      "should handle sort field and direction" in {
        val testData = """ {"sortField" : "field", "sortDirection" : "direction"} """
        val item = testData.parseJson.convertTo[LibrarySearchParams]
        assertResult(Some("field")) {item.sortField}
        assertResult(Some("direction")) {item.sortDirection}
      }
      "should handle missing parameters" in {
        val testData = """ {} """
        val params = testData.parseJson.convertTo[LibrarySearchParams]
        assertResult(0) {params.from}
        assertResult(10) {params.size}
        assertResult(None) {params.searchString}
        assertResult(Map.empty) {params.filters}
        assertResult(Map.empty) {params.fieldAggregations}
        assertResult(None) {params.sortField}
        assertResult(None) {params.sortDirection}
      }
    }
    "when marshalling to json" - {
      "should handle arbitrary namespace" in {
        val testData = LibrarySearchParams(None, Map.empty, Map.empty)
        assertResult("""{"filters":{},"fieldAggregations":{},"from":0,"size":10}""".parseJson) {testData.toJson.toString.parseJson}
      }
      "should handle sort field and direction" in {
        val testData = LibrarySearchParams(None, Map.empty, Map.empty, sortField=Some("field"), sortDirection=Some("direction"))
        assertResult("""{"filters":{},"fieldAggregations":{},"from":0,"size":10,"sortField":"field","sortDirection":"direction"}""".parseJson) {testData.toJson.toString.parseJson}
      }
    }
  }

  "ESInternalType model" - {
    val modelObject = ESInternalType("string",index="not_analyzed",include_in_all=false)
    val modelJsonStr = """{"type":"string","index":"not_analyzed","include_in_all":false}"""

    "when unmarshalling from json" - {
      "using parseJson" in {
        val item = modelJsonStr.parseJson.convertTo[ESPropertyFields]
        assert(item.isInstanceOf[ESInternalType])
        assertResult(modelObject) {item.asInstanceOf[ESInternalType]}
      }
      "using impESPropertyFields" in {
        val item = impESPropertyFields.read(modelJsonStr.parseJson)
        assert(item.isInstanceOf[ESInternalType])
        assertResult(modelObject) {
          item.asInstanceOf[ESInternalType]
        }
      }

    }
    "when marshalling to json" - {
      "using toJson" in {
        assertResult(modelJsonStr) {
          modelObject.toJson.toString
        }
      }
      "using impESPropertyFields" in {
        assertResult(modelJsonStr) {
          impESPropertyFields.write(modelObject).toString
        }
      }
    }
  }

  "ESNestedType model" - {
    val modelObject = ESNestedType(Map(
      "foo" -> ESInnerField("string"),
      "bar" -> ESInnerField("integer", include_in_all=Some(false))
    ))
    val modelJsonStr = """{"properties":{"foo":{"type":"string"},"bar":{"type":"integer","include_in_all":false}},"type":"nested"}"""

    "when unmarshalling from json" - {
      "using parseJson" in {
        val item = modelJsonStr.parseJson.convertTo[ESPropertyFields]
        assert(item.isInstanceOf[ESNestedType])
        assertResult(modelObject) {
          item.asInstanceOf[ESNestedType]
        }
      }
      "using impESPropertyFields" in {
        val item = impESPropertyFields.read(modelJsonStr.parseJson)
        assert(item.isInstanceOf[ESNestedType])
        assertResult(modelObject) {
          item.asInstanceOf[ESNestedType]
        }
      }
    }
    "when marshalling to json" - {
      "using toJson" in {
        assertResult(modelJsonStr) {
          modelObject.toJson.toString
        }
      }
      "using impESPropertyFields" in {
        assertResult(modelJsonStr) {
          impESPropertyFields.write(modelObject).toString
        }
      }
    }
  }

}
