package org.broadinstitute.dsde.firecloud.utils

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.filematch.result.{
  FailedMatchResult,
  FileMatchResult,
  PartialMatchResult,
  SuccessfulMatchResult
}
import org.broadinstitute.dsde.firecloud.filematch.strategy.{FileMatchStrategy, IlluminaPairedEndStrategy}

import java.nio.file.Path
import scala.annotation.tailrec

// *******************************************************************************************************************
// POC of file-matching for AJ-2025:
// Given a list of files, pair those files based on Illumina single end and paired end read patterns
// *******************************************************************************************************************

class FileMatcher extends LazyLogging {

  private val matchingStrategies: List[FileMatchStrategy] = List(new IlluminaPairedEndStrategy())

  def pairFiles(fileList: List[String]): List[FileMatchResult] = {
    // convert fileList to pathList
    val pathList = fileList.map(file => new java.io.File(file).toPath)
    pairPaths(pathList)
  }

  def pairPaths(pathList: List[Path]): List[FileMatchResult] = {
    // sort the incoming list for better performance (???)
    val paths = pathList.sorted

    pairNextPath(paths, List())
  }

  @tailrec
  private def pairNextPath(remainingPathList: List[Path], pairsFound: List[FileMatchResult]): List[FileMatchResult] =
    remainingPathList match {
      case Nil =>
        // no files left to match. Just return what we have found so far.
        pairsFound
      case nextFile :: remaining =>
        // try to match the nextFile to our known recognition strategies
        tryMatchStrategies(nextFile, remaining) match {
          case success: SuccessfulMatchResult =>
            // we found a pair for the current file, remove the pair from the remaining file list
            pairNextPath(remaining.filterNot(_.equals(success.secondFile)), pairsFound.appended(success))
          case failure => pairNextPath(remaining, pairsFound.appended(failure))
        }

    }

  private def tryMatchStrategies(mainFile: Path, remainingPathList: List[Path]): FileMatchResult = {
    // does the current file hit on any of our file-matching patterns? Iterate over the matching strategies
    // and return the first successful match result.
    val strategyHit = matchingStrategies.collectFirst(strategy =>
      strategy.matchFirstFile(mainFile) match {
        case success: SuccessfulMatchResult => success
      }
    )

    strategyHit match {
      case Some(desiredResult: SuccessfulMatchResult) =>
        // The current file `mainFile` hits on our file-matching patterns.
        // The `desiredResult` contains the name of the file that we hope `mainFile` can be paired with.
        // Now, check if the matching second file in `desiredResult` actually exists in the original file list.
        if (remainingPathList.exists(path => path.equals(desiredResult.secondFile))) {
          desiredResult
        } else {
          PartialMatchResult(desiredResult.firstFile, desiredResult.id)
        }
      case _ =>
        // the current file isn't recognized by any of our file-matching patterns, or the file it should be paired
        // with doesn't exist.
        FailedMatchResult(mainFile)
    }

  }

}
