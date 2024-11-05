package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, FileMatchResult, SuccessfulMatchResult}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class IlluminaPairedEndStrategySpec extends AnyFreeSpec with Matchers {

  val strategy = new IlluminaPairedEndStrategy

  /**
    * Naming conventions for Illumina single end and paired end read patterns. Examples of files recognized:
    *
    * SampleName_S1_L001_R1_001.fastq.gz -> SampleName_S1_L001_R2_001.fastq.gz
    */

  // set of input, expected test cases
  val recognizedTestCases: Map[String, FileMatchResult] = Map(
    "Sample1_01.fastq.gz" -> SuccessfulMatchResult(toPath("Sample1_01.fastq.gz"),
                                                   toPath("Sample1_02.fastq.gz"),
                                                   "Sample1"
    ),
    "someSubdirectory/Sample1_01.fastq.gz" -> SuccessfulMatchResult(toPath("someSubdirectory/Sample1_01.fastq.gz"),
                                                                    toPath("someSubdirectory/Sample1_02.fastq.gz"),
                                                                    "Sample1"
    ),
    "sample42_1.fastq.gz" -> SuccessfulMatchResult(toPath("sample42_1.fastq.gz"),
                                                   toPath("sample42_2.fastq.gz"),
                                                   "sample42"
    ),
    "sample01_R1.fastq.gz" -> SuccessfulMatchResult(toPath("sample01_R1.fastq.gz"),
                                                    toPath("sample01_R2.fastq.gz"),
                                                    "sample01"
    ),
    "/foo/bar/789a_F.fastq.gz" -> SuccessfulMatchResult(toPath("/foo/bar/789a_F.fastq.gz"),
                                                        toPath("/foo/bar/789a_R.fastq.gz"),
                                                        "789a"
    ),
    "sample01_R1.fastq" -> SuccessfulMatchResult(toPath("sample01_R1.fastq"), toPath("sample01_R2.fastq"), "sample01"),
    "SampleName_S1_L001_R1_001.fastq.gz" -> SuccessfulMatchResult(toPath("SampleName_S1_L001_R1_001.fastq.gz"),
                                                                  toPath("SampleName_S1_L001_R2_001.fastq.gz"),
                                                                  "SampleName_S1_L001"
    )
  )

  val unrecognizedInputs: List[String] =
    List("my-cat-picture.png", "Sample1_01.fastq.gz/is/a/bad/directory/name", "Sample1_01.fasta.gz", "Sample1_01.bam")

  "IlluminaPairedEndStrategy" - {
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

  private def toPath(input: String) = new java.io.File(input).toPath

}
