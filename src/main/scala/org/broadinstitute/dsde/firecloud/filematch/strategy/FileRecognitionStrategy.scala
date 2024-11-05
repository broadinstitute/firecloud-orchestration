package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.FileMatchResult

import java.nio.file.Path

/**
  * Marker trait representing file-naming conventions used for pairing matched reads.
  */
trait FileRecognitionStrategy {

  def matchFirstFile(path: Path): FileMatchResult

}
