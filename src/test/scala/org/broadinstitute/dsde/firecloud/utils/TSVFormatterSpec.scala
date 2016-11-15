package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.core.GetEntitiesWithType.EntityWithType
import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.{Entity, ModelSchema}
import org.broadinstitute.dsde.firecloud.model.ModelJsonProtocol._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Inspectors, Matchers}

import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.io.Source

class TSVFormatterSpec extends FreeSpec with ScalaFutures with Matchers with Inspectors {

  "TSVFormatter" - {

    "Sparse data fields should pass for" - {

      "Entity and Membership Set Data" in {
        val samples = List(Entity(entityType = Some("sample"), entityName = Some("sample_01")),
          Entity(entityType = Some("sample"), entityName = Some("sample_02")),
          Entity(entityType = Some("sample"), entityName = Some("sample_03")),
          Entity(entityType = Some("sample"), entityName = Some("sample_04")))
        val sampleSetList = List(
          EntityWithType("sample_set_1", "sample_set", Some(
            Map("foo" -> "bar".toJson, "samples" -> samples.toJson)
          )),
          EntityWithType("sample_set_2", "sample_set", Some(
            Map("bar" -> "foo".toJson, "samples" -> samples.toJson)
          )),
          EntityWithType("sample_set_3", "sample_set", Some(
            Map("baz" -> "?#*".toJson, "samples" -> samples.toJson)
          )))
        testEntityDataSet("sample_set", sampleSetList, None)
        testMembershipDataSet("sample_set", sampleSetList, sampleSetList.size * samples.size)
      }
    }

    "Incorrectly formatted membership data should fail" in {
      val sampleSetList = List(
        EntityWithType(
          "sample_set_1",
          "sample_set",
          Some(Map("foo" -> "bar".toJson, "samples" -> List("sample1", "sample2", "sample3").toJson))))
      intercept[RuntimeException] {
        TSVFormatter.makeMembershipTsvString(sampleSetList, "sample_set", "samples")
      }
    }

    "Sample tests should pass for" - {

      "Entity Data" in {
        val sampleAtts = {
          Map(
            "sample_type" -> "Blood".toJson,
            "header_1" -> MockUtils.randomAlpha().toJson,
            "header_2" -> MockUtils.randomAlpha().toJson,
            "participant" -> """{"entityType":"participant","entityName":"participant_name"}""".parseJson
          )
        }
        val sampleList = List(
          EntityWithType("sample_01", "sample", Some(sampleAtts)),
          EntityWithType("sample_02", "sample", Some(sampleAtts)),
          EntityWithType("sample_03", "sample", Some(sampleAtts)),
          EntityWithType("sample_04", "sample", Some(sampleAtts))
        )

        val results = testEntityDataSet("sample", sampleList, None)
        results should contain theSameElementsAs Seq("entity:sample_id", "sample_type", "header_1", "header_2", "participant_id")
        results.head should be ("entity:sample_id")

        Seq(
          IndexedSeq("header_2", "does_not_exist", "header_1"),
          IndexedSeq("header_2", "sample_id", "header_1"),
          IndexedSeq("header_1", "header_2")
        ).foreach { requestedHeaders =>
          val resultsWithSpecificHeaders = testEntityDataSet("sample", sampleList, Option(requestedHeaders))
          resultsWithSpecificHeaders should contain theSameElementsInOrderAs Seq("entity:sample_id") ++ requestedHeaders.filterNot(_.equals("sample_id"))
        }
      }

      "Set Data" in {
        val samples = List(Entity(entityType = Some("sample"), entityName = Some("sample_01")),
          Entity(entityType = Some("sample"), entityName = Some("sample_02")),
          Entity(entityType = Some("sample"), entityName = Some("sample_03")),
          Entity(entityType = Some("sample"), entityName = Some("sample_04")))
        val sampleSetAtts = {
          Map("samples" -> samples.toJson)
        }
        val sampleSetList = List(EntityWithType("sample_set_1", "sample_set", Some(sampleSetAtts)))
        testMembershipDataSet("sample_set", sampleSetList, samples.size)
      }
    }

    "Participant tests should pass for" - {

      "Entity Data" in {
        val participantAtts1 = {
          Map(
            "participant_id" -> """{"entityType":"participant","entityName":"1143"}""".parseJson,
            "gender" -> "F".toJson,
            "age" -> "52".toJson
          )
        }
        val participantAtts2 = {
          Map(
            "participant_id" -> """{"entityType":"participant","entityName":"1954"}""".parseJson,
            "gender" -> "M".toJson,
            "age" -> "61".toJson
          )
        }
        val participantList = List(EntityWithType("1143", "participant", Some(participantAtts1)),
          EntityWithType("1954", "participant", Some(participantAtts2)))

        val results = testEntityDataSet("participant", participantList, None)
        results should contain theSameElementsAs Seq("entity:participant_id", "participant_id", "gender", "age")
        results.head should be ("entity:participant_id")
      }

      "Set Data" in {
        val participants = List(Entity(entityType = Some("participant"), entityName = Some("subject_HCC1143")),
          Entity(entityType = Some("participant"), entityName = Some("subject_HCC1144")))
        val participantSetAtts = {
          Map("participants" -> participants.toJson)
        }
        val participantSetList = List(EntityWithType("participant_set_1", "participant_set", Some(participantSetAtts)))
        testMembershipDataSet("participant_set", participantSetList, participants.size)
      }
    }

    "Pair tests should pass for" - {

      "Entity data" in {
        val pairAtts1 = {
          Map(
            "case_sample" -> """{"entityType": "sample", "entityName": "345"}""".parseJson,
            "control_sample" -> """{"entityType": "sample", "entityName": "456"}""".parseJson,
            "participant" -> """{"entityType":"participant","entityName":"1143"}""".parseJson,
            "header_1" -> MockUtils.randomAlpha().toJson
          )
        }
        val pairAtts2 = {
          Map(
            "case_sample" -> """{"entityType": "sample", "entityName": "567"}""".parseJson,
            "control_sample" -> """{"entityType": "sample", "entityName": "678"}""".parseJson,
            "participant" -> """{"entityType":"participant","entityName":"1954"}""".parseJson,
            "header_1" -> MockUtils.randomAlpha().toJson
          )
        }
        val pairList = List(EntityWithType("1", "pair", Some(pairAtts1)),
          EntityWithType("2", "pair", Some(pairAtts2)))

        val results = testEntityDataSet("pair", pairList, None)
        results should contain theSameElementsAs Seq("entity:pair_id", "case_sample_id", "control_sample_id", "participant_id", "header_1")
        results.head should be ("entity:pair_id")
      }

      "Set data" in {
        val pairs = List(Entity(entityType = Some("pair"), entityName = Some("1")),
          Entity(entityType = Some("pair"), entityName = Some("2")))
        val pairSetAtts = {
          Map("pairs" -> pairs.toJson)
        }
        val pairSetList = List(EntityWithType("pair_set_1", "pair_set", Some(pairSetAtts)))
        testMembershipDataSet("pair_set", pairSetList, pairs.size)
      }
    }

  }

  private def testEntityDataSet(entityType: String, entities: List[EntityWithType], requestedHeaders: Option[IndexedSeq[String]]) = {
    val headerRenamingMap: Map[String, String] = ModelSchema.getAttributeExportRenamingMap(entityType)
      .getOrElse(Map.empty[String, String])
    val tsv = TSVFormatter.makeEntityTsvString(entities, entityType, requestedHeaders)

    tsv shouldNot be(empty)

    val lines: List[String] = Source.fromString(tsv).getLines().toList

    // Resultant data should have a header and one line per entity:
    lines.size should equal(entities.size + 1)

    val headers = lines.head.split("\t")

    // Make sure that all of the post-rename values exist in the list of headers from the TSV file
    // for all non-set types
    if (ModelSchema.getCollectionMemberType(entityType).isFailure) {
      forAll (headerRenamingMap.values.toList) { x => headers should contain(x) }
    }

    // Conversely, the TSV file should not have any of the pre-rename values
    forAll (headerRenamingMap.keys.toList) { x => headers shouldNot contain(x) }

    // Check that all lines have the same number of columns as the header.
    lines foreach( _.split("\t", -1).size should equal(headers.size) )

    headers
  }

  private def testMembershipDataSet(
    entityType: String,
    entities: List[EntityWithType],
    expectedSize: Int
  ): Unit = {
    val collectionMemberType = ModelSchema.getPlural(ModelSchema.getCollectionMemberType(entityType).get.get)
    val tsv = TSVFormatter.makeMembershipTsvString(entities, entityType, collectionMemberType.getOrElse(entityType))
    tsv shouldNot be(empty)

    val lines: List[String] = Source.fromString(tsv).getLines().toList
    lines.size should equal(expectedSize + 1) // Add 1 for the header line.

    lines map { _.split("\t", -1).size should equal(2) }

  }

}
