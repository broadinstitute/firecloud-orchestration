package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, SuccessfulMatchResult}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class OntSingleReadStrategySpec extends AnyFreeSpec with Matchers {

  val strategy = new OntSingleReadStrategy

  // set of input, expected test cases
  val recognizedTestCases: Map[String, SuccessfulMatchResult] = Map(
    "Complete_barcode01.fastq.gz" -> SuccessfulMatchResult(toPath("Complete_barcode01.fastq.gz"),
                                                           Paths.get(""),
                                                           "Complete_barcode01"
    ),
    "Complete_barcode02.clean.fastq" -> SuccessfulMatchResult(toPath("Complete_barcode02.clean.fastq"),
                                                              Paths.get(""),
                                                              "Complete_barcode02"
    ),
    "Incomplete_barcode03.fastq.gz" -> SuccessfulMatchResult(toPath("Incomplete_barcode03.fastq.gz"),
                                                             Paths.get(""),
                                                             "Incomplete_barcode03"
    ),
    "Repeat_barcode4.fastq.gz" -> SuccessfulMatchResult(toPath("Repeat_barcode4.fastq.gz"),
                                                        Paths.get(""),
                                                        "Repeat_barcode4"
    ),
    "barcode5.fastq.gz" -> SuccessfulMatchResult(toPath("barcode5.fastq.gz"), Paths.get(""), "barcode5")
  )

  val unrecognizedInputs: List[String] =
    List("unknown_file.txt", "Complete_barcode01.fasta.gz", "Incomplete_barcode03.bam")

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
