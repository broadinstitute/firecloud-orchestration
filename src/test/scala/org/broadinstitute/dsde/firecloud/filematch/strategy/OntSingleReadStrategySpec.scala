package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, SuccessfulMatchResult}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class OntSingleReadStrategySpec extends AnyFreeSpec with Matchers {

  val strategy = new OntSingleReadStrategy

  // set of input, expected test cases
  val recognizedTestCases: Map[String, SuccessfulMatchResult] = Map(
    "Complete_barcode01.fastq.gz" -> SuccessfulMatchResult(toPath("Complete_barcode01.fastq.gz"), "Complete_barcode01"),
    "Complete_barcode02.clean.fastq" -> SuccessfulMatchResult(toPath("Complete_barcode02.clean.fastq"),
                                                              "Complete_barcode02"
    ),
    "Incomplete_barcode03.fastq.gz" -> SuccessfulMatchResult(toPath("Incomplete_barcode03.fastq.gz"),
                                                             "Incomplete_barcode03"
    ),
    "Repeat_barcode4.fastq.gz" -> SuccessfulMatchResult(toPath("Repeat_barcode4.fastq.gz"), "Repeat_barcode4"),
    "barcode5.fastq.gz" -> SuccessfulMatchResult(toPath("barcode5.fastq.gz"), "barcode5"),
    "Repeat_barcode6.clean.fastq.gz" -> SuccessfulMatchResult(toPath("Repeat_barcode6.clean.fastq.gz"),
                                                              "Repeat_barcode6"
    ),
    "Repeat_BARCODE6.clean.fastq.gz" -> SuccessfulMatchResult(toPath("Repeat_BARCODE6.clean.fastq.gz"),
                                                              "Repeat_BARCODE6"
    ),
    "Foo_barcode1.fastq" -> SuccessfulMatchResult(toPath("Foo_barcode1.fastq"), "Foo_barcode1"),
    "Foo_barcode1.fastq.gz" -> SuccessfulMatchResult(toPath("Foo_barcode1.fastq.gz"), "Foo_barcode1"),
    "Foo_barcode1.clean.fastq" -> SuccessfulMatchResult(toPath("Foo_barcode1.clean.fastq"), "Foo_barcode1"),
    "Foo_barcode1.clean.fastq.gz" -> SuccessfulMatchResult(toPath("Foo_barcode1.clean.fastq.gz"), "Foo_barcode1"),
    "barcode2.fastq" -> SuccessfulMatchResult(toPath("barcode2.fastq"), "barcode2"),
    "barcode2.fastq.gz" -> SuccessfulMatchResult(toPath("barcode2.fastq.gz"), "barcode2"),
    "barcode2.clean.fastq" -> SuccessfulMatchResult(toPath("barcode2.clean.fastq"), "barcode2"),
    "barcode2.clean.fastq.gz" -> SuccessfulMatchResult(toPath("barcode2.clean.fastq.gz"), "barcode2"),
    "Barcode38934278247843.fastq" -> SuccessfulMatchResult(toPath("Barcode38934278247843.fastq"),
                                                           "Barcode38934278247843"
    ),
    "Barcode38934278247843.fastq.gz" -> SuccessfulMatchResult(toPath("Barcode38934278247843.fastq.gz"),
                                                              "Barcode38934278247843"
    ),
    "Barcode38934278247843.clean.fastq" -> SuccessfulMatchResult(toPath("Barcode38934278247843.clean.fastq"),
                                                                 "Barcode38934278247843"
    ),
    "Barcode38934278247843.clean.fastq.gz" -> SuccessfulMatchResult(toPath("Barcode38934278247843.clean.fastq.gz"),
                                                                    "Barcode38934278247843"
    )
  )

  val unrecognizedInputs: List[String] =
    List(
      "unknown_file.txt",
      "Complete_barcode01.fasta.gz",
      "Incomplete_barcode03.bam",
      "barcode.fastq",
      "barcode.fastq.gz",
      "barcode.clean.fastq",
      "barcode.clean.fastq.gz",
      "Complete_barcode01.fastq.gz.gz.gz.gz.gz.gz"
    )

  "OntSingleReadStrategy" - {
    recognizedTestCases foreach { case (inputString, expectedMatchResult) =>
      s"should hit on recognized input file $inputString" in {
        val matchResult = strategy.matchFirstFile(toPath(inputString))
        matchResult shouldBe expectedMatchResult
      }
    }

    unrecognizedInputs foreach { inputString =>
      s"should miss on unrecognized input file $inputString" in {
        val matchResult = strategy.matchFirstFile(toPath(inputString))
        matchResult shouldBe FailedMatchResult(toPath(inputString))
      }
    }
  }

  private def toPath(input: String) = Paths.get(input)
}
