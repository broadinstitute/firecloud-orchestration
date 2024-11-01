package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.FileMatchResult

import java.nio.file.Path

trait FileRecognitionStrategy {

  def matchFirstFile(path: Path): FileMatchResult

}
