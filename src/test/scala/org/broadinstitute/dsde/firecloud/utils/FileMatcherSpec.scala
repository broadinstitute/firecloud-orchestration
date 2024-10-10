package org.broadinstitute.dsde.firecloud.utils

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class FileMatcherSpec extends AnyFreeSpec with Matchers {

  "FileMatcher" - {
    "pairFiles" - {
      "should match when input is ideal" in {
        val input = List("Sample1_01.fastq.gz", "Sample1_02.fastq.gz", "Sample2_01.fastq.gz", "Sample2_02.fastq.gz")

        val expected = List(
          PairMatch("Sample1_01.fastq.gz", Option("Sample1_02.fastq.gz"), Option("sample"), Option("1")),
          PairMatch("Sample2_01.fastq.gz", Option("Sample2_02.fastq.gz"), Option("sample"), Option("2"))
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
      "should still return results when no matches exist" in {
        val input = List("Sample1_01.fastq.gz", "Sample2_01.fastq.gz", "Sample3_01.fastq.gz", "Sample4_01.fastq.gz")

        val expected = List(
          PairMatch("Sample1_01.fastq.gz", None, Option("sample"), Option("1")),
          PairMatch("Sample2_01.fastq.gz", None, Option("sample"), Option("2")),
          PairMatch("Sample3_01.fastq.gz", None, Option("sample"), Option("3")),
          PairMatch("Sample4_01.fastq.gz", None, Option("sample"), Option("4"))
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
      "should return results when some but not all matches exist" in {
        val input = List("Sample1_01.fastq.gz", "Sample2_01.fastq.gz", "Sample1_02.fastq.gz", "Sample4_01.fastq.gz")

        val expected = List(
          PairMatch("Sample1_01.fastq.gz", Option("Sample1_02.fastq.gz"), Option("sample"), Option("1")),
          PairMatch("Sample2_01.fastq.gz", None, Option("sample"), Option("2")),
          PairMatch("Sample4_01.fastq.gz", None, Option("sample"), Option("4"))
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
      "should return results when some inputs dont hit the regex at all" in {
        val input = List("Sample1_01.fastq.gz", "Sample2_01.fastq.gz", "Sample1_02.fastq.gz", "anotherfile.txt",
          "my-cat-picture.jpg")

        val expected = List(
          PairMatch("Sample1_01.fastq.gz", Option("Sample1_02.fastq.gz"), Option("sample"), Option("1")),
          PairMatch("Sample2_01.fastq.gz", None, Option("sample"), Option("2")),
          PairMatch("anotherfile.txt", None, None, None),
          PairMatch("my-cat-picture.jpg", None, None, None)
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
    }
  }

}
