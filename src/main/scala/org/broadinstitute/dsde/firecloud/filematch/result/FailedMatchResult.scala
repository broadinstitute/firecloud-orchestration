package org.broadinstitute.dsde.firecloud.filematch.result

import com.google.common.annotations.VisibleForTesting

import java.nio.file.Path

/**
  * FileMatchResult indicating that the file did not hit on any known pattern.
  */
case class FailedMatchResult(firstFile: Path) extends FileMatchResult {}

@VisibleForTesting
object FailedMatchResult {
  def fromString(firstFile: String): FailedMatchResult =
    FailedMatchResult(new java.io.File(firstFile).toPath)
}
