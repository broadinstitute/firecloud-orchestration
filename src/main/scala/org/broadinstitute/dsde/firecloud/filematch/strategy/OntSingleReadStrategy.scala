package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, FileMatchResult, SuccessfulMatchResult}
import OntSingleReadStrategy.{fastqGz, FILE_STARTS_ENDINGS}

import java.nio.file.{Path, Paths}

class OntSingleReadStrategy extends FileRecognitionStrategy {

  override def matchFirstFile(path: Path): FileMatchResult = {
    // search known patterns for a "read1" file
    val foundMatch = FILE_STARTS_ENDINGS.find { case (key, values) =>
      path.getFileName.toString.startsWith(key) && values.exists(value => path.toString.endsWith(value))
    }

    foundMatch match {
      // we found a "read1"
      case Some((_, values)) =>
        // find the matching value
        val value = values.find(value => path.toString.endsWith(value)).get
        // generate the id: strip the suffix from the filename.
        val id = path.getFileName.toString.replace(value, "")

        SuccessfulMatchResult(path, Paths.get(""), id)

      // the file is not recognized
      case None => FailedMatchResult(path)
    }
  }
}

object OntSingleReadStrategy {
  private val fastqGz = ".fastq.gz"
  // if the first file starts with ${key} and ends with any of the values in the list, then this is a single read file
  val FILE_STARTS_ENDINGS: Map[String, List[String]] = Map(
    "Complete_barcode" -> List(fastqGz, ".clean.fastq"),
    "Incomplete_barcode" -> List(fastqGz),
    "Repeat_barcode" -> List(fastqGz),
    "barcode" -> List(fastqGz)
  )
}
