package org.broadinstitute.dsde.firecloud.filematch.result

import com.google.common.annotations.VisibleForTesting

import java.nio.file.Path

/**
  * MatchResult indicating that the file successfully hit a known pattern.
  */
case class SuccessfulMatchResult(firstFile: Path, secondFile: Path, id: String) extends FileMatchResult {}

@VisibleForTesting
object SuccessfulMatchResult {
  def fromStrings(firstFile: String, secondFile: String, id: String): SuccessfulMatchResult =
    SuccessfulMatchResult(new java.io.File(firstFile).toPath, new java.io.File(secondFile).toPath, id)
}
