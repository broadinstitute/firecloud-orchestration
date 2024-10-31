package org.broadinstitute.dsde.firecloud.filematch.strategy

import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, FileMatchResult, SuccessfulMatchResult}
import org.broadinstitute.dsde.firecloud.filematch.strategy.IlluminaPairedEndStrategy.FILE_ENDINGS

import java.nio.file.Path

/*
Sample1_01.fastq.gz -> Sample1_02.fastq.gz
sample01_1.fastq.gz -> sample01_2.fastq.gz
sample01_R1.fastq.gz -> sample01_R2.fastq.gz
sample01_F.fastq.gz -> sample01_R.fastq.gz
sample01_R1.fastq -> sample01_R2.fastq
SampleName_S1_L001_R1_001.fastq.gz -> SampleName_S1_L001_R2_001.fastq.gz
 */

object IlluminaPairedEndStrategy {
  // if the first file ends with ${key}, then the second file should end with ${value}
  val FILE_ENDINGS: Map[String, String] = Map(
    "_01.fastq.gz" -> "_02.fastq.gz",
    "_1.fastq.gz" -> "_2.fastq.gz",
    "_R1.fastq.gz" -> "_R2.fastq.gz",
    "_F.fastq.gz" -> "_R.fastq.gz",
    "_R1.fastq" -> "_R2.fastq",
    "_R1_001.fastq.gz" -> "_R2_001.fastq.gz"
  )
}

class IlluminaPairedEndStrategy extends FileMatchStrategy {
  override def matchFirstFile(path: Path): FileMatchResult = {
    val foundMatch = FILE_ENDINGS.find { case (key, _) => path.toString.endsWith(key) }

    foundMatch match {
      case Some((key, value)) =>
        // generate the id: strip the suffix from the filename.
        val id = path.getFileName.toString.replace(key, "")
        // generate the second filename: replace the first suffix with the second suffix
        val secondFile = new java.io.File(path.toString.replace(key, value))
        SuccessfulMatchResult(path, secondFile.toPath, id)

      case None => FailedMatchResult(path)
    }
  }
}
