package org.broadinstitute.dsde.firecloud.model

import org.broadinstitute.dsde.firecloud.model.DataUse.{DiseaseOntologyNodeId, ResearchPurpose}
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import spray.json.JsString
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

/**
  * Created by ahaessly on 1/19/17.
  */
class ElasticSearchSpec  extends AnyFreeSpec with Assertions {

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
      "should create params with missing research purpose if none specified" in {
        val testData = """ {"filters": {"library:datatype":["cancer"]},"fieldAggregations":{"library:indication":5}} """
        val item = testData.parseJson.convertTo[LibrarySearchParams]
        assert(item.researchPurpose.isEmpty, s"research purpose was non-empty: ${item.researchPurpose}")
      }
      "should handle a valid research purpose" in {
        val testData =
          """ {"filters": {"library:datatype":["cancer"]},"fieldAggregations":{"library:indication":5},
            |  "researchPurpose": {"NMDS": true, "NCTRL": false, "NAGR": true, "POA": false, "NCU": true,
            |    "DS": ["http://purl.obolibrary.org/obo/DOID_123", "http://purl.obolibrary.org/obo/DOID_456"]}
            | } """.stripMargin
        val item = testData.parseJson.convertTo[LibrarySearchParams]
        val expectedResearchPurpose = ResearchPurpose(
          Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_123"), DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_456")),
          NMDS=true, NCTRL=false, NAGR=true, POA=false, NCU=true
        )
        assertResult(Some(expectedResearchPurpose)) {item.researchPurpose}
      }
      "should reject an incomplete research purpose" in {
        // testData is missing NMDS
        val testData =
          """ {"filters": {"library:datatype":["cancer"]},"fieldAggregations":{"library:indication":5},
            |  "researchPurpose": {"NCTRL": false, "NAGR": true, "POA": false, "NCU": true,
            |    "DS": ["http://purl.obolibrary.org/obo/DOID_123", "http://purl.obolibrary.org/obo/DOID_456"]}
            | } """.stripMargin
        intercept[DeserializationException] {
          testData.parseJson.convertTo[LibrarySearchParams]
        }
      }
      "should reject a complete research purpose with invalid node ids" in {
        // testData has bad DOID uris
        val testData =
          """ {"filters": {"library:datatype":["cancer"]},"fieldAggregations":{"library:indication":5},
            |  "researchPurpose": {"NMDS": true, "NCTRL": false, "NAGR": true, "POA": false, "NCU": true,
            |    "DS": ["DOID:123"]}
            | } """.stripMargin
        intercept[IllegalArgumentException] {
          testData.parseJson.convertTo[LibrarySearchParams]
        }
      }
    }
    "when marshalling to json" - {
      "should handle arbitrary namespace" in {
        val testData = LibrarySearchParams(None, Map.empty, None, Map.empty)
        assertResult("""{"filters":{},"fieldAggregations":{},"from":0,"size":10}""".parseJson) {testData.toJson.toString.parseJson}
      }
      "should handle sort field and direction" in {
        val testData = LibrarySearchParams(None, Map.empty, None, Map.empty, sortField=Some("field"), sortDirection=Some("direction"))
        assertResult("""{"filters":{},"fieldAggregations":{},"from":0,"size":10,"sortField":"field","sortDirection":"direction"}""".parseJson) {testData.toJson.toString.parseJson}
      }
      "should properly serialize research purpose" in {
        val researchPurpose = ResearchPurpose(
          Seq(DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_123"), DiseaseOntologyNodeId("http://purl.obolibrary.org/obo/DOID_456")),
          NMDS=true, NCTRL=false, NAGR=true, POA=false, NCU=true
        )
        val testData = LibrarySearchParams(None, Map.empty, Some(researchPurpose), Map.empty)
        assertResult(
          """{"filters":{},
            | "researchPurpose":{"POA":false,"NCU":true,"NAGR":true,"NMDS":true,"NCTRL":false,
            |  "DS":["http://purl.obolibrary.org/obo/DOID_123","http://purl.obolibrary.org/obo/DOID_456"]},
            | "fieldAggregations":{},"from":0,"size":10}""".stripMargin.parseJson) {testData.toJson.toString.parseJson}
      }
    }
  }

  "ESInternalType model" - {
    val modelObject = ESInternalType("string",index="not_analyzed",include_in_all=false)
    val modelJsonStr = """{"include_in_all":false,"index":"not_analyzed","type":"string"}"""

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
    val modelJsonStr = """{"properties":{"foo":{"type":"string"},"bar":{"include_in_all":false,"type":"integer"}},"type":"nested"}"""

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
