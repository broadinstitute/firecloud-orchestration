package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model._
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
        val samples = AttributeEntityReferenceList(Seq(
          AttributeEntityReference(entityType = "sample", entityName = "sample_01"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_02"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_03"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_04")))
        val sampleSetList = List(
          RawlsEntity("sample_set_1", "sample_set", Map(AttributeName.withDefaultNS("foo") -> AttributeString("bar"), AttributeName.withDefaultNS("samples") -> samples)),
          RawlsEntity("sample_set_2", "sample_set", Map(AttributeName.withDefaultNS("bar") -> AttributeString("foo"), AttributeName.withDefaultNS("samples") -> samples)),
          RawlsEntity("sample_set_3", "sample_set", Map(AttributeName.withDefaultNS("baz") -> AttributeString("?#*"), AttributeName.withDefaultNS("samples") -> samples)))

        testEntityDataSet("sample_set", sampleSetList, None)
        testMembershipDataSet("sample_set", sampleSetList, sampleSetList.size * samples.list.size)
      }
    }

    "Sample tests should pass for" - {

      "Entity Data" in {
        val sampleAtts = {
          Map(
            AttributeName.withDefaultNS("sample_type") -> AttributeString("Blood"),
            AttributeName.withDefaultNS("header_1") -> AttributeString(MockUtils.randomAlpha()),
            AttributeName.withDefaultNS("header_2") -> AttributeString(MockUtils.randomAlpha()),
            AttributeName.withDefaultNS("participant") -> AttributeEntityReference("participant","participant_name")
          )
        }
        val sampleList = List(
          RawlsEntity("sample_01", "sample", sampleAtts),
          RawlsEntity("sample_02", "sample", sampleAtts),
          RawlsEntity("sample_03", "sample", sampleAtts),
          RawlsEntity("sample_04", "sample", sampleAtts)
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
        val samples = AttributeEntityReferenceList(Seq(
          AttributeEntityReference(entityType = "sample", entityName = "sample_01"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_02"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_03"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_04")))
        val sampleSetAtts = {
          Map(AttributeName.withDefaultNS("samples") -> samples)
        }
        val sampleSetList = List(RawlsEntity("sample_set_1", "sample_set", sampleSetAtts))
        testMembershipDataSet("sample_set", sampleSetList, samples.list.size)
      }
    }

    "Participant tests should pass for" - {

      "Entity Data" in {
        val participantAtts1 = {
          Map(
            AttributeName.withDefaultNS("participant_id") -> AttributeEntityReference(entityType = "participant", entityName = "1143"),
            AttributeName.withDefaultNS("gender") -> AttributeString("F"),
            AttributeName.withDefaultNS("age") -> AttributeString("52")
          )
        }
        val participantAtts2 = {
          Map(
            AttributeName.withDefaultNS("participant_id") -> AttributeEntityReference(entityType = "participant", entityName = "1954"),
            AttributeName.withDefaultNS("gender") -> AttributeString("M"),
            AttributeName.withDefaultNS("age") -> AttributeString("61")
          )
        }
        val participantList = List(RawlsEntity("1143", "participant", participantAtts1),
          RawlsEntity("1954", "participant", participantAtts2))

        val results = testEntityDataSet("participant", participantList, None)
        results should contain theSameElementsAs Seq("entity:participant_id", "participant_id", "gender", "age")
        results.head should be ("entity:participant_id")
      }

      "Set Data" in {
        val participants = AttributeEntityReferenceList(Seq(
          AttributeEntityReference(entityType = "participant", entityName = "subject_HCC1143"),
          AttributeEntityReference(entityType = "participant", entityName = "subject_HCC1144")))
        val participantSetAtts = {
          Map(AttributeName.withDefaultNS("participants") -> participants)
        }
        val participantSetList = List(RawlsEntity("participant_set_1", "participant_set", participantSetAtts))
        testMembershipDataSet("participant_set", participantSetList, participants.list.size)
      }
    }

    "Pair tests should pass for" - {

      "Entity data" in {
        val pairAtts1 = {
          Map(
            AttributeName.withDefaultNS("case_sample") -> AttributeEntityReference(entityType = "sample", entityName = "345"),
            AttributeName.withDefaultNS("control_sample") -> AttributeEntityReference(entityType = "sample", entityName = "456"),
            AttributeName.withDefaultNS("participant") -> AttributeEntityReference(entityType = "participant", entityName = "1143"),
            AttributeName.withDefaultNS("header_1") -> AttributeString(MockUtils.randomAlpha())
          )
        }
        val pairAtts2 = {
          Map(
            AttributeName.withDefaultNS("case_sample") -> AttributeEntityReference(entityType = "sample", entityName = "567"),
            AttributeName.withDefaultNS("control_sample") -> AttributeEntityReference(entityType = "sample", entityName = "678"),
            AttributeName.withDefaultNS("participant") -> AttributeEntityReference(entityType = "participant", entityName = "1954"),
            AttributeName.withDefaultNS("header_1") -> AttributeString(MockUtils.randomAlpha())
          )
        }
        val pairList = List(RawlsEntity("1", "pair", pairAtts1),
          RawlsEntity("2", "pair", pairAtts2))

        val results = testEntityDataSet("pair", pairList, None)
        results should contain theSameElementsAs Seq("entity:pair_id", "case_sample_id", "control_sample_id", "participant_id", "header_1")
        results.head should be ("entity:pair_id")
      }

      "Set data" in {
        val pairs = AttributeEntityReferenceList(Seq(
          AttributeEntityReference(entityType = "pair", entityName = "1"),
          AttributeEntityReference(entityType = "pair", entityName = "2")))
        val pairSetAtts = {
          Map(AttributeName.withDefaultNS("pairs") -> pairs)
        }
        val pairSetList = List(RawlsEntity("pair_set_1", "pair_set", pairSetAtts))
        testMembershipDataSet("pair_set", pairSetList, pairs.list.size)
      }
    }

  }

  private def testEntityDataSet(entityType: String, entities: List[RawlsEntity], requestedHeaders: Option[IndexedSeq[String]]) = {
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
    entities: List[RawlsEntity],
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
