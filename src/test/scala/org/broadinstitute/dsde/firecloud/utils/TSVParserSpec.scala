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

  "EntityClient.improveAttributeNames" should "fix up the names of attributes for certain reference types" in {
    val input = Seq( "case_sample_id" -> None, "case_sample_id" -> Option("foo"), "case_sample_id" -> Option("sample"), "foo" -> Option("bar"))
    val expect = Seq( "case_sample_id" -> None, "case_sample" -> Option("foo"), "case" -> Option("sample"), "foo" -> Option("bar"))
    assertResult(expect) { EntityClient.improveAttributeNames(input) }
  }
}
