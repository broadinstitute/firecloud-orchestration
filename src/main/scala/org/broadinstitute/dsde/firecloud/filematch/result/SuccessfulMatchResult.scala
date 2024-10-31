package org.broadinstitute.dsde.firecloud.filematch.result

import java.nio.file.Path

/**
  * MatchResult indicating that the first file successfully hit a known pattern.
  */
case class SuccessfulMatchResult(firstFile: Path, secondFile: Path, id: String) extends FileMatchResult {}
