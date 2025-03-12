package org.broadinstitute.dsde.firecloud.filematch.result

import com.google.common.annotations.VisibleForTesting

import java.nio.file.Path

/**
  * FileMatchResult indicating that the file successfully hit a known pattern.
  */
case class SuccessfulMatchResult(firstFile: Path, secondFile: Path, id: String) extends FileMatchResult {
  // convert this SuccessfulMatchResult to a PartialMatchResult
  def toPartial: PartialMatchResult = PartialMatchResult(firstFile, id)
}

@VisibleForTesting
object SuccessfulMatchResult {
  def fromStrings(firstFile: String, secondFile: String, id: String): SuccessfulMatchResult =
    SuccessfulMatchResult(new java.io.File(firstFile).toPath, new java.io.File(secondFile).toPath, id)

  def apply(firstFile: Path, id: String): SuccessfulMatchResult =
    SuccessfulMatchResult(firstFile, null, id)
}
