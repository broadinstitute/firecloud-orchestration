package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, FileMatchResult, SuccessfulMatchResult}
import OntSingleReadStrategy.FILE_CONTAIN_ENDINGS

import java.nio.file.{Path, Paths}
import scala.util.matching.Regex

class OntSingleReadStrategy extends FileRecognitionStrategy {

  // Precompile regex patterns: (?i).*barcode\d+.*(\.fastq|\.fastq\.gz)$
  private val compiledPatterns: Map[String, Regex] = FILE_CONTAIN_ENDINGS.map { case (key, values) =>
    key -> new Regex(s"(?i).*${Regex.quote(key)}\\d+.*(${values.map(Regex.quote).mkString("|")})$$")
  }

  override def matchFirstFile(path: Path): FileMatchResult = {
    val fileName = path.getFileName.toString

    // Use precompiled regex patterns to match the file name
    val foundMatch = compiledPatterns.find { case (_, regex) =>
      regex.findFirstIn(fileName).isDefined
    }

    foundMatch match {
      // we found a "read1"
      case Some((key, _)) =>
        // Extract the matching value directly from the regex match
        val value = FILE_CONTAIN_ENDINGS(key).find(value => fileName.endsWith(value)).get
        // generate the id: strip the value and any characters before the extension from the filename.
        val id = fileName.stripSuffix(value).replaceAll("\\..*$", "")
        SuccessfulMatchResult(path, Paths.get(""), id)

      // the file is not recognized
      case None => FailedMatchResult(path)
    }
  }
}

object OntSingleReadStrategy {
  // if the file contains ${key} and ends with any value in the list, it's an ONT single read file
  val FILE_CONTAIN_ENDINGS: Map[String, List[String]] = Map(
    "barcode" -> List(".fastq", ".fastq.gz")
  )
}
