package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, FileMatchResult, SuccessfulMatchResult}
import OntSingleReadStrategy.FILE_CONTAIN_ENDINGS

import java.nio.file.{Path, Paths}
import scala.util.matching.Regex

class OntSingleReadStrategy extends FileRecognitionStrategy {

  override def matchFirstFile(path: Path): FileMatchResult = {
    val fileName = path.getFileName.toString

    // Used regex to match the file name containing the key and ending with any of the specified values
    val foundMatch = FILE_CONTAIN_ENDINGS.find { case (key, values) =>
      val regex = new Regex(s".*${Regex.quote(key)}.*(${values.map(Regex.quote).mkString("|")})$$")
      regex.findFirstIn(fileName).isDefined
    }

    foundMatch match {
      // we found a "read1"
      case Some((_, values)) =>
        // find the matching value
        val value = values.find(value => fileName.endsWith(value)).get
        // generate the id: strip the value from the filename.
        val id = fileName.replace(value, "")
        SuccessfulMatchResult(path, Paths.get(""), id)

      // the file is not recognized
      case None => FailedMatchResult(path)
    }
  }
}

object OntSingleReadStrategy {
  // if the first file contains ${key} and ends with any of the values in the list, then this is a single read file
  val FILE_CONTAIN_ENDINGS: Map[String, List[String]] = Map(
    "Complete_barcode" -> List(".clean.fastq"),
    "barcode" -> List(".fastq", ".fastq.gz")
  )
}
