package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, FileMatchResult, SuccessfulMatchResult}
import OntSingleReadStrategy.PATTERN

import java.nio.file.{Path, Paths}
import scala.util.matching.Regex

class OntSingleReadStrategy extends FileRecognitionStrategy {

  override def matchFirstFile(path: Path): FileMatchResult =
    PATTERN.findFirstMatchIn(path.toString) match {
      case Some(regexMatch) => SuccessfulMatchResult(path, Paths.get(""), regexMatch.group(1))
      case None             => FailedMatchResult(path)
    }
}

object OntSingleReadStrategy {
  // if the file contains barcode### and ends with .fastq or .fastq.gz, it's an ONT single read file
  val PATTERN: Regex = """(?i)(.*barcode\d+).*\.fastq(?:.gz)?$""".r
}
