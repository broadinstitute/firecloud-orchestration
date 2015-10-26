package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.ModelSchema
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Inspectors, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.io.Source

class TSVFormatterSpec extends FreeSpec with ScalaFutures with Matchers with Inspectors {

  "TSVFormatter" - {

    "should work with sample data" in {
      val sampleAtts = {
        Map(
          "sample_type" -> "Blood".toJson,
          "header_1" -> MockUtils.randomAlpha().toJson,
          "header_2" -> MockUtils.randomAlpha().toJson,
          "participant_id" -> """{"entityType":"participant","entityName":"participant_name"}""".parseJson
        )
      }
      val sampleList = List(
        EntityWithType("sample_01", "sample", Some(sampleAtts)),
        EntityWithType("sample_02", "sample", Some(sampleAtts)),
        EntityWithType("sample_03", "sample", Some(sampleAtts)),
        EntityWithType("sample_04", "sample", Some(sampleAtts))
      )
      testDataSet("sample", sampleList)
    }

    "should work with pair data" in {
      val pairAtts = {
        Map(
          "case_sample_id" -> """{"entityType": "sample", "entityName": "HCC1143"}""".parseJson,
          "control_sample_id" -> """{"entityType": "sample", "entityName": "HCC1143_BL"}""".parseJson,
          "participant_id" -> """{"entityType":"participant","entityName":"subject_HCC1143"}""".parseJson,
          "header_1" -> MockUtils.randomAlpha().toJson,
          "header_2" -> MockUtils.randomAlpha().toJson
        )
      }
      val pairList = List(EntityWithType("pair_01", "pair", Some(pairAtts)))
      testDataSet("pair", pairList)
    }

    "should work with participant_set data" in {
      val participantSetAtts = {
        Map(
          "disease" -> "lung cancer".toJson,
          "case_sample_id" -> """{"entityType": "sample", "entityName": "HCC1143"}""".parseJson,
          "control_sample_id" -> """{"entityType": "sample", "entityName": "HCC1143_BL"}""".parseJson,
          "participant_id" -> """{"entityType":"participant_set","entityName":"subject_HCC1143"}""".parseJson,
          "gender" -> "male".toJson,
          "header_1" -> MockUtils.randomAlpha().toJson,
          "header_2" -> MockUtils.randomAlpha().toJson
        )
      }
      val participantSetList = List(EntityWithType("participant_set_01", "participant_set", Some(participantSetAtts)))
      testDataSet("participant_set", participantSetList)
    }

    "should work with sample_set data" in {
      val sampleSetAtts = {
        Map(
          "sample_id" -> """{"entityType": "sample", "entityName": "HCC1143"}""".parseJson,
          "control_sample_id" -> """{"entityType": "sample", "entityName": "HCC1143_BL"}""".parseJson,
          "participant_id" -> """{"entityType":"participant_set","entityName":"subject_HCC1143"}""".parseJson,
          "gender" -> "male".toJson,
          "header_1" -> MockUtils.randomAlpha().toJson
        )
      }
      val sampleSetList = List(
        EntityWithType("sample_set_01", "sample_set", Some(sampleSetAtts)),
        EntityWithType("sample_set_02", "sample_set", Some(sampleSetAtts)),
        EntityWithType("sample_set_03", "sample_set", Some(sampleSetAtts))
      )
      testDataSet("sample_set", sampleSetList)
    }

    "should work with pair_set data" in {
      val pairSetAtts = {
        Map(
          "pair_id" -> """{"entityType": "pair", "entityName": "HCC1143_pair"}""".parseJson,
          "sample_id" -> """{"entityType": "sample", "entityName": "HCC1143"}""".parseJson,
          "header_1" -> MockUtils.randomAlpha().toJson,
          "header_2" -> MockUtils.randomAlpha().toJson
        )
      }
      val pairSetList = List(EntityWithType("pair_set_01", "pair_set", Some(pairSetAtts)))
      testDataSet("pair_set", pairSetList)
    }

  }

  private def testDataSet(entityType: String, entities: List[EntityWithType]): Unit = {
    val headerRenamingMap: Map[String, String] = ModelSchema.getAttributeRenamingMap(entityType)
      .getOrElse(Map.empty[String, String])
    val tsv = TSVFormatter.makeTsvString(entities, entityType)

    tsv shouldNot be(empty)

    val lines: List[String] = Source.fromString(tsv).getLines().toList

    // Resultant data should have a header and one line per entity:
    lines.size should equal(entities.size + 1)

    val headers = lines.head.split("\t")

    // Make sure that all of the post-rename values exist in the list of headers from the TSV file
    forAll (headerRenamingMap.values.toList) { x => headers should contain(x) }

    // Conversely, the TSV file should not have any of the pre-rename values
    forAll (headerRenamingMap.keys.toList) { x => headers shouldNot contain(x) }
  }

}
