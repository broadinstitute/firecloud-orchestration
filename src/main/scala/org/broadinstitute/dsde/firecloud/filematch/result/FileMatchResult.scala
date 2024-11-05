package org.broadinstitute.dsde.firecloud.filematch.result

import java.nio.file.Path

/**
  * Marker trait for failed/partial/successful file-matching results
  */
trait FileMatchResult {
  def firstFile: Path
}
