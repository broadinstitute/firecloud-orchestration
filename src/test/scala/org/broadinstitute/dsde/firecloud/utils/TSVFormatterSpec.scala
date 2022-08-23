package org.broadinstitute.dsde.firecloud.utils

import org.broadinstitute.dsde.firecloud.mock.MockUtils
import org.broadinstitute.dsde.firecloud.model.{FirecloudModelSchema, ModelSchema}
import org.broadinstitute.dsde.firecloud.service.TsvTypes
import org.broadinstitute.dsde.firecloud.service.TsvTypes.TsvType
import org.broadinstitute.dsde.rawls.model._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import spray.json.{JsArray, JsFalse, JsNumber, JsObject, JsString, JsTrue}

import scala.io.Source
import scala.language.postfixOps

class TSVFormatterSpec extends AnyFreeSpec with ScalaFutures with Matchers with Inspectors {

  implicit val modelSchema: ModelSchema = FirecloudModelSchema

  "TSVFormatter" - {

    "Sparse data fields should pass for" - {

      "Entity and Membership Set Data" in {
        val samples = AttributeEntityReferenceList(Seq(
          AttributeEntityReference(entityType = "sample", entityName = "sample_01"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_02"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_03"),
          AttributeEntityReference(entityType = "sample", entityName = "sample_04")))
        val sampleSetList = List(
          Entity("sample_set_1", "sample_set", Map(AttributeName.withDefaultNS("foo") -> AttributeString("bar"), AttributeName.withDefaultNS("samples") -> samples)),
          Entity("sample_set_2", "sample_set", Map(AttributeName.withDefaultNS("bar") -> AttributeString("foo"), AttributeName.withDefaultNS("samples") -> samples)),
          Entity("sample_set_3", "sample_set", Map(AttributeName.withDefaultNS("baz") -> AttributeString("?#*"), AttributeName.withDefaultNS("samples") -> samples)))

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
          Entity("sample_01", "sample", sampleAtts),
          Entity("sample_02", "sample", sampleAtts),
          Entity("sample_03", "sample", sampleAtts),
          Entity("sample_04", "sample", sampleAtts)
        )

        val results = testEntityDataSet("sample", sampleList, None)
        results should contain theSameElementsAs Seq("entity:sample_id", "sample_type", "header_1", "header_2", "participant")
        results.head should be ("entity:sample_id")

        val results2 = testEntityDataSet("sample", sampleList, Option(IndexedSeq.empty))
        results2 should contain theSameElementsAs Seq("entity:sample_id", "sample_type", "header_1", "header_2", "participant")
        results2.head should be ("entity:sample_id")

        val results3 = testEntityDataSet("sample", sampleList, Option(IndexedSeq("")))
        results3 should contain theSameElementsAs Seq("entity:sample_id", "sample_type", "header_1", "header_2", "participant")
        results3.head should be ("entity:sample_id")

        Seq(
          IndexedSeq("header_2", "does_not_exist", "header_1"),
          IndexedSeq("header_2", "sample_id", "header_1"),
          IndexedSeq("header_1", "header_2"),
          IndexedSeq("header_1")
        ).foreach { requestedHeaders =>
          val resultsWithSpecificHeaders = testEntityDataSet("sample", sampleList, Option(requestedHeaders), TsvTypes.UPDATE)
          resultsWithSpecificHeaders should contain theSameElementsInOrderAs Seq("update:sample_id") ++ requestedHeaders.filterNot(_.equals("sample_id"))
        }

        testEntityDataSet("sample", sampleList, Option(IndexedSeq("participant"))) should contain theSameElementsInOrderAs Seq("entity:sample_id", "participant")

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
        val sampleSetList = List(Entity("sample_set_1", "sample_set", sampleSetAtts))
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
        val participantList = List(Entity("1143", "participant", participantAtts1),
          Entity("1954", "participant", participantAtts2))

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
        val participantSetList = List(Entity("participant_set_1", "participant_set", participantSetAtts))
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
        val pairList = List(Entity("1", "pair", pairAtts1),
          Entity("2", "pair", pairAtts2))

        val results = testEntityDataSet("pair", pairList, None)
        results should contain theSameElementsAs Seq("entity:pair_id", "case_sample", "control_sample", "participant", "header_1")
        results.head should be ("entity:pair_id")
      }

      "Set data" in {
        val pairs = AttributeEntityReferenceList(Seq(
          AttributeEntityReference(entityType = "pair", entityName = "1"),
          AttributeEntityReference(entityType = "pair", entityName = "2")))
        val pairSetAtts = {
          Map(AttributeName.withDefaultNS("pairs") -> pairs)
        }
        val pairSetList = List(Entity("pair_set_1", "pair_set", pairSetAtts))
        testMembershipDataSet("pair_set", pairSetList, pairs.list.size)
      }
    }

    "Values containing tabs should be quoted" in {
      val entityType = "sample"
      val attrs1 = {
        Map(
          AttributeName.withDefaultNS("nowhitespace") -> AttributeString("abcdefg"),
          AttributeName.withDefaultNS("tabs") -> AttributeString("this\tvalue\thas\ttabs"),
          AttributeName.withDefaultNS("spaces") -> AttributeString("this value has spaces")
        )
      }
      val attrs2 = {
        Map(
          AttributeName.withDefaultNS("nowhitespace") -> AttributeString("hijklm"),
          AttributeName.withDefaultNS("tabs") -> AttributeString("another\tvalue\twith\ttabs"),
          AttributeName.withDefaultNS("spaces") -> AttributeString("another value with spaces")
        )
      }
      val entities = List(
        Entity("1", entityType, attrs1),
        Entity("2", entityType, attrs2))

      val tsvHeaders = TSVFormatter.makeEntityHeaders(entityType, List("nowhitespace", "tabs", "spaces"), None)
      val tsvRows = TSVFormatter.makeEntityRows(entityType, entities, tsvHeaders)

      tsvRows shouldBe Seq(
        Seq("1", "abcdefg", "\"this\tvalue\thas\ttabs\"", "this value has spaces"),
        Seq("2", "hijklm", "\"another\tvalue\twith\ttabs\"", "another value with spaces"),
      )
    }

    val tsvSafeAttributeTestData = Map(
      AttributeString("foo") -> "foo",
      AttributeString(""""quoted string"""") -> """"quoted string"""",
      AttributeNumber(123.45) -> "123.45",
      AttributeBoolean(true) -> "true",
      AttributeBoolean(false) -> "false",
      AttributeValueList(Seq(AttributeString("one"), AttributeString("two"), AttributeString("three"))) -> """["one","two","three"]""",
      AttributeValueRawJson(JsObject(Map("foo" -> JsString("bar"), "baz" -> JsNumber(123)))) -> """{"foo":"bar","baz":123}""",
      AttributeEntityReference("targetType", "targetName") -> """{"entityType":"targetType","entityName":"targetName"}""",
      AttributeEntityReferenceList(Seq(
        AttributeEntityReference("type1", "name1"),
        AttributeEntityReference("type2", "name2"))) -> """[{"entityType":"type1","entityName":"name1"},{"entityType":"type2","entityName":"name2"}]"""
    )
    "tsvSafeAttribute() method" - {
      tsvSafeAttributeTestData foreach {
        case (input, expected) =>
          s"should stringify correctly for input $input" in {
            TSVFormatter.tsvSafeAttribute(input) shouldBe expected
          }
      }
    }

  }

  private def testEntityDataSet(entityType: String, entities: List[Entity], requestedHeaders: Option[IndexedSeq[String]], tsvType: TsvType = TsvTypes.ENTITY) = {

    val allHeaders = entities flatMap { e =>
      e.attributes map { a => a._1.name }
    } distinct
    val tsvHeaders = TSVFormatter.makeEntityHeaders(entityType, allHeaders, requestedHeaders)
    val tsvRows = TSVFormatter.makeEntityRows(entityType, entities, tsvHeaders)
    val tsv = TSVFormatter.exportToString(tsvHeaders, tsvRows)

    tsv shouldNot be(empty)

    val lines: List[String] = Source.fromString(tsv).getLines().toList

    // Resultant data should have a header and one line per entity:
    lines.size should equal(entities.size + 1)

    val headers = lines.head.split("\t")

    // make sure all required headers are present
    headers(0) should be(s"${tsvType.toString}:${entityType}_id")

    // Check that all lines have the same number of columns as the header.
    lines foreach( _.split("\t", -1).length should equal(headers.size) )

    headers
  }

  private def testMembershipDataSet(
    entityType: String,
    entities: List[Entity],
    expectedSize: Int
  ): Unit = {
    val tsvHeaders = TSVFormatter.makeMembershipHeaders(entityType)
    val tsvRows = TSVFormatter.makeMembershipRows(entityType, entities)
    val tsv = TSVFormatter.exportToString(tsvHeaders, tsvRows.toIndexedSeq)
    tsv shouldNot be(empty)

    val lines: List[String] = Source.fromString(tsv).getLines().toList
    lines.size should equal(expectedSize + 1) // Add 1 for the header line.

    lines foreach { _.split("\t", -1).length should equal(2) }

    lines.head.split("\t") should be(Array(s"${TsvTypes.MEMBERSHIP.toString}:${entityType}_id", FirecloudModelSchema.getCollectionMemberType(entityType).get.get))
  }

}
