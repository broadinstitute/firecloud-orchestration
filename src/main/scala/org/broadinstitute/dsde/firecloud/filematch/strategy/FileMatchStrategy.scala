package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.FileMatchResult

import java.nio.file.Path

trait FileMatchStrategy {

  def matchFirstFile(filename: Path): FileMatchResult

}
