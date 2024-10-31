package org.broadinstitute.dsde.firecloud.utils

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.filematch.result.{FailedMatchResult, SuccessfulMatchResult}
import org.broadinstitute.dsde.firecloud.filematch.strategy.{FileMatchStrategy, IlluminaPairedEndStrategy}

import java.nio.file.Path
import scala.annotation.tailrec
import scala.util.matching.Regex

// *******************************************************************************************************************
// POC of file-matching for AJ-2025:
// Given a list of files, pair those files based on Illumina single end and paired end read patterns
// *******************************************************************************************************************

object PairMatch {
  def apply(mainFile: String, matchedFile: Option[String], id: Option[String]): PairMatch =
    new PairMatch(new java.io.File(mainFile).toPath, matchedFile.map(x => new java.io.File(x).toPath), id)
}

case class PairMatch(
  mainFile: Path,
  matchedFile: Option[Path],
  id: Option[String]
)

class FileMatcher extends LazyLogging {

  private val matchingStrategies: List[FileMatchStrategy] = List(new IlluminaPairedEndStrategy())

  def pairFiles(fileList: List[String]): List[PairMatch] = {
    // convert fileList to pathList
    val pathList = fileList.map(file => new java.io.File(file).toPath)
    pairPaths(pathList)
  }

  def pairPaths(pathList: List[Path]): List[PairMatch] = {
    // sort the incoming list for better performance (???)
    val paths = pathList.sorted

    pairNextPath(paths, List())
  }

  @tailrec
  private def pairNextPath(remainingPathList: List[Path], pairsFound: List[PairMatch]): List[PairMatch] =
    remainingPathList match {
      case Nil =>
        // no files left to match. Just return what we have found so far.
        pairsFound
      case nextFile :: remaining =>
        val matchResult = tryMatchStrategies(nextFile, remaining)
        // if we found a pair for the current file, remove the pair from the remaining file list
        matchResult.matchedFile match {
          case None => pairNextPath(remaining, pairsFound.appended(matchResult))
          case Some(pair) =>
            pairNextPath(remaining.filterNot(_.equals(pair)), pairsFound.appended(matchResult))
        }

    }

  private def tryMatchStrategies(mainFile: Path, remainingPathList: List[Path]): PairMatch = {
    // does the current file hit on any of our file-matching patterns? Iterate over the matching strategies
    // and return the first successful match result.
    val strategyHit = matchingStrategies.collectFirst(strategy =>
      strategy.matchFirstFile(mainFile) match {
        case success: SuccessfulMatchResult => success
      }
    )

    strategyHit match {
      case Some(matchResult: SuccessfulMatchResult) =>
        // the current file hits on our file-matching patterns. Use that pattern to try to find a pair.
        val id = Some(matchResult.id)
        val maybePair = remainingPathList.find(path => path.equals(matchResult.secondFile))
        PairMatch(matchResult.firstFile, maybePair, id)
      case _ =>
        // the current file isn't recognized by any of our file-matching patterns. Return it without any pairing.
        PairMatch(mainFile, None, None)
    }

  }

}
