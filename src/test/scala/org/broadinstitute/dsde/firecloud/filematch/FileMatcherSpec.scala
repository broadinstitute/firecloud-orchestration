package org.broadinstitute.dsde.firecloud.filematch

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, PartialMatchResult, SuccessfulMatchResult}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class FileMatcherSpec extends AnyFreeSpec with Matchers {

  "FileMatcher" - {
    "pairFiles" - {
      "should match when input is ideal" in {
        val input = List("Sample1_01.fastq.gz", "Sample1_02.fastq.gz", "Sample2_01.fastq.gz", "Sample2_02.fastq.gz")

        val expected = List(
          SuccessfulMatchResult.fromStrings("Sample1_01.fastq.gz", "Sample1_02.fastq.gz", "Sample1"),
          SuccessfulMatchResult.fromStrings("Sample2_01.fastq.gz", "Sample2_02.fastq.gz", "Sample2")
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
      "should still return results when no matches exist" in {
        val input = List("Sample1_01.fastq.gz", "Sample2_01.fastq.gz", "Sample3_01.fastq.gz", "Sample4_01.fastq.gz")

        val expected = List(
          PartialMatchResult.fromStrings("Sample1_01.fastq.gz", "Sample1"),
          PartialMatchResult.fromStrings("Sample2_01.fastq.gz", "Sample2"),
          PartialMatchResult.fromStrings("Sample3_01.fastq.gz", "Sample3"),
          PartialMatchResult.fromStrings("Sample4_01.fastq.gz", "Sample4")
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
      "should return results when some but not all matches exist" in {
        val input = List("Sample1_01.fastq.gz", "Sample2_01.fastq.gz", "Sample1_02.fastq.gz", "Sample4_01.fastq.gz")

        val expected = List(
          SuccessfulMatchResult.fromStrings("Sample1_01.fastq.gz", "Sample1_02.fastq.gz", "Sample1"),
          PartialMatchResult.fromStrings("Sample2_01.fastq.gz", "Sample2"),
          PartialMatchResult.fromStrings("Sample4_01.fastq.gz", "Sample4")
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
      "should return results when some inputs dont hit the regex at all" in {
        val input = List("Sample1_01.fastq.gz",
                         "Sample2_01.fastq.gz",
                         "Sample1_02.fastq.gz",
                         "anotherfile.txt",
                         "my-cat-picture.jpg"
        )

        val expected = List(
          SuccessfulMatchResult.fromStrings("Sample1_01.fastq.gz", "Sample1_02.fastq.gz", "Sample1"),
          PartialMatchResult.fromStrings("Sample2_01.fastq.gz", "Sample2"),
          FailedMatchResult.fromString("anotherfile.txt"),
          FailedMatchResult.fromString("my-cat-picture.jpg")
        )
        val actual = new FileMatcher().pairFiles(input)

        actual shouldBe expected
      }
    }
  }

}
