package org.broadinstitute.dsde.firecloud.utils

import org.scalatest.FlatSpec
import org.broadinstitute.dsde.firecloud.EntityClient
import org.broadinstitute.dsde.firecloud.mock.{MockTSVLoadFiles, MockTSVStrings}

/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/23/2015
 * Time: 15:57
 */

class TSVParserSpec extends FlatSpec {
  "TSV parser" should "throw an exception when given an empty file to parse" in {
    intercept[RuntimeException] {
      TSVParser.parse(MockTSVStrings.empty)
    }
  }

  "TSV parser" should "throw an exception when given a bunch of blank lines to parse" in {
    intercept[RuntimeException] {
      TSVParser.parse(MockTSVStrings.onlyNewlines)
    }
  }

  it should "throw an exception when a data line has too many fields" in {
    intercept[RuntimeException] {
      TSVParser.parse(MockTSVStrings.rowTooLong)
    }
  }

  it should "throw an exception when a data line has too few fields" in {
    intercept[RuntimeException] {
      TSVParser.parse(MockTSVStrings.rowTooShort)
    }
  }

  it should "throw an exception when a data line has extra tabs at the end" in {
    intercept[RuntimeException] {
      TSVParser.parse(MockTSVStrings.tooManyTabs)
    }
  }

  it should "load a one-line file" in {
    val parseResult = MockTSVLoadFiles.validOneLine
    assertResult(parseResult) {
      TSVParser.parse(MockTSVStrings.validOneLine)
    }
  }

  it should "be fine with a bunch of newlines at the end of file" in {
    val parseResult = MockTSVLoadFiles.validOneLine
    assertResult(parseResult) {
      TSVParser.parse(MockTSVStrings.trailingNewlines)
    }
  }

  it should "load a multi-line file" in {
    val parseResult = MockTSVLoadFiles.validMultiLine
    assertResult(parseResult) {
      TSVParser.parse(MockTSVStrings.validMultiline)
    }
  }
  "EntityClient.improveAttributeNames" should "fix up the names of attributes for certain reference types for pairs" in {
    val entityType: String = "pair"
    val requiredAttributes: Map[String, String] = Map("case_sample_id" -> "sample",
      "control_sample_id" -> "sample",
      "participant_id" -> "participant")

    val input = Seq(
      "entity:pair_id", // first column stripped off when parsing attributes
      "case_sample_id",
      "control_sample_id",
      "participant_id",
      "some_other_id",
      "ref_dict",
      "ref_fasta")

    val expect = Seq(
      "case_sample" -> Some("sample"),
      "control_sample" -> Some("sample"),
      "participant" -> Some("participant"),
      "some_other_id" -> None,
      "ref_dict" -> None,
      "ref_fasta" -> None)

    assertResult(expect) {
      EntityClient.improveAttributeNames(entityType, input, requiredAttributes)
    }
  }

  it should "fix up the names of attributes for certain reference types for samples" in {
    val entityType: String = "sample"
    val requiredAttributes: Map[String, String] = Map(
      "participant_id" -> "participant")

    val input = Seq(
      "entity:sample_id", // first column stripped off when parsing attributes
      "participant_id",
      "some_other_id",
      "ref_dict",
      "ref_fasta")

    val expect = Seq(
      "participant" -> Some("participant"),
      "some_other_id" -> None,
      "ref_dict" -> None,
      "ref_fasta" -> None)

    assertResult(expect) {
      EntityClient.improveAttributeNames(entityType, input, requiredAttributes)
    }
  }


  it should "fix up the names of attributes for certain reference types for participant sets" in {
    val entityType: String = "participant_set"
    val requiredAttributes: Map[String, String] = Map.empty

    val input = Seq(
      "entity:participant_set_id", // first column stripped off when parsing attributes
      "participant_id",
      "some_other_id")

    val expect = Seq(
      "participant" -> None,
      "some_other_id" -> None)
    
    assertResult(expect) {
      EntityClient.improveAttributeNames(entityType, input, requiredAttributes)
    }
  }
}
