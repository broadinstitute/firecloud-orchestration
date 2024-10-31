package org.broadinstitute.dsde.firecloud.filematch.result

import java.nio.file.Path

/**
  * MatchResult indicating that the first file did not hit on any known pattern.
  */
case class FailedMatchResult(firstFile: Path) extends FileMatchResult {}
