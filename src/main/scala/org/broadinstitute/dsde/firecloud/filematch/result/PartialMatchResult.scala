package org.broadinstitute.dsde.firecloud.filematch.result

import com.google.common.annotations.VisibleForTesting

import java.nio.file.Path

/**
  * MatchResult indicating that the file successfully hit a known pattern, but no paired file could be found.
  */
case class PartialMatchResult(firstFile: Path, id: String) extends FileMatchResult {}

@VisibleForTesting
object PartialMatchResult {
  def fromStrings(firstFile: String, id: String): PartialMatchResult =
    PartialMatchResult(new java.io.File(firstFile).toPath, id)
}
