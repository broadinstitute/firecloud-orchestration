package org.broadinstitute.dsde.firecloud.utils

import org.scalatest.FlatSpec

/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/23/2015
 * Time: 15:57
 */

class TSVParserSpec extends FlatSpec {
  "TSV parser" should "throw an exception when given an empty file to parse" in {
    intercept[RuntimeException] {
      TSVParser.parse("")
    }
  }

  "TSV parser" should "throw an exception when given a bunch of blank lines to parse" in {
    intercept[RuntimeException] {
      TSVParser.parse("\n\n\n\n\n\n")
    }
  }

  it should "throw an exception when a data line has too many fields" in {
    intercept[RuntimeException] {
      TSVParser.parse(
        List(
          List("foo", "bar", "baz").mkString("\t"),
          List("this", "line's", "fine").mkString("\t"),
          List("this", "line's", "too", "long").mkString("\t")
        ).mkString("\n"))
    }
  }

  it should "throw an exception when a data line has too few fields" in {
    intercept[RuntimeException] {
      TSVParser.parse(
        List(
          List("foo", "bar", "baz").mkString("\t"),
          List("this", "line's", "fine").mkString("\t"),
          List("too", "short").mkString("\t")
        ).mkString("\n"))
    }
  }

  it should "throw an exception when a data line has extra tabs at the end" in {
    intercept[RuntimeException] {
      TSVParser.parse(
        List(
          List("foo", "bar", "baz").mkString("\t"),
          List("this", "line's", "fine").mkString("\t"),
          List("too", "many", "tabs\t").mkString("\t")
        ).mkString("\n"))
    }
  }

  it should "load a one-line file" in {
    val parseResult = TSVLoadFile("foo", Seq("foo", "bar", "baz"), Seq(Array("woop", "de", "doo")))
    assertResult(parseResult) {
      TSVParser.parse(
        List(
          List("foo", "bar", "baz").mkString("\t"),
          List("woop", "de", "doo").mkString("\t")
        ).mkString("\n"))
    }
  }

  it should "be fine with a bunch of newlines at the end of file" in {
    val parseResult = TSVLoadFile("foo", Seq("foo", "bar", "baz"), Seq(Array("woop", "de", "doo")))
    assertResult(parseResult) {
      TSVParser.parse(
        List(
          List("foo", "bar", "baz").mkString("\t"),
          List("woop", "de", "doo").mkString("\t")
        ).mkString("\n") + "\n\n\n\n")
    }
  }

  it should "load a multi-line file" in {
    val parseResult = TSVLoadFile("foo", Seq("foo", "bar", "baz"),
      Seq(
        Array("woop", "de", "doo"),
        Array("hip", "hip", "hooray")))

    assertResult(parseResult) {
      TSVParser.parse(
        List(
          List("foo", "bar", "baz").mkString("\t"),
          List("woop", "de", "doo").mkString("\t"),
          List("hip", "hip", "hooray").mkString("\t")
        ).mkString("\n"))
    }
  }
}
