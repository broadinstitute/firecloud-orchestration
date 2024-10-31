package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.SuccessfulMatchResult
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class IlluminaPairedEndStrategySpec extends AnyFreeSpec with Matchers {

  val strategy = new IlluminaPairedEndStrategy

  "toPath utility function" - {
    "should work" in {
      val input = "Sample1_01.fastq.gz"
      val actual = toPath(input)
      actual.toString shouldBe input
    }
  }

  "endsWith" - {
    "should work" in {
      val input = "Sample1_01.fastq.gz"
      val actual = toPath(input)
      actual.toString.endsWith("_01.fastq.gz") shouldBe true
    }
  }

  "pathlist sorting" - {
    "should work" in {
      val input = List("aaa1", "aaa3", "aaa2", "aaa6", "aaa5", "aaa4")
      val pathList = input.map(x => new java.io.File(x).toPath)
      val actual = pathList.sorted

      val expected = List("aaa1", "aaa2", "aaa3", "aaa4", "aaa5", "aaa6").map(x => new java.io.File(x).toPath)

      actual shouldBe expected
    }
  }

  "IlluminaPairedEndStrategy" - {
    "should hit on a known file" in {
      val input = "Sample1_01.fastq.gz"
      val matchResult = strategy.matchFirstFile(toPath(input))
      matchResult shouldBe a[SuccessfulMatchResult]

      matchResult shouldBe SuccessfulMatchResult(toPath("Sample1_01.fastq.gz"),
                                                 toPath("Sample1_02.fastq.gz"),
                                                 "Sample1"
      )
    }
  }

  def toPath(input: String) = new java.io.File(input).toPath

}
