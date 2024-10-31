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

case class PairPattern(
  mainFile: Regex,
  pairedFile: String => Regex
)

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

//  val patterns = List(
//    // Sample1_01.fastq.gz, Sample1_02.fastq.gz
//    PairPattern("(?<basetype>[a-zA-Z_]+)(?<id>\\d+)_01.fastq.gz".r, baseName => s"${baseName}_02.fastq.gz".r)
//  )

  val matchingStrategies: List[FileMatchStrategy] = List(new IlluminaPairedEndStrategy())

//  def pairFiles(fileList: List[String]): List[PairMatch] = {
//    // sort the incoming list for better performance (???)
//    val files = fileList.sorted
//
//    pairNextFile(files, List())
//  }
//
//  @tailrec
//  private def pairNextFile(remainingFileList: List[String], pairsFound: List[PairMatch]): List[PairMatch] =
//    remainingFileList match {
//      case Nil =>
//        // no files left to match. Just return what we have found so far.
//        pairsFound
//      case nextFile :: remaining =>
//        val matchResult = tryToMatch(nextFile, remaining)
//        // if we found a pair for the current file, remove the pair from the remaining file list
//        matchResult.matchedFile match {
//          case None => pairNextFile(remaining, pairsFound.appended(matchResult))
//          case Some(pair) =>
//            pairNextFile(remaining.filterNot(_.equals(pair)), pairsFound.appended(matchResult))
//        }
//
//    }

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

//  private def tryToMatch(mainFile: String, remainingFileList: List[String]): PairMatch = {
//    val patternHit = patterns.find(pairPattern => pairPattern.mainFile.matches(mainFile))
//    patternHit match {
//      case None =>
//        // the current file isn't recognized by any of our file-matching patterns. Return it without any pairing.
//        PairMatch(mainFile, None, None)
//      case Some(pairPattern) =>
//        // the current file hits on our file-matching patterns. Use that pattern to try to find a pair.
//        // TODO: this is redundant, can be optimized
//        val maybeMatch = pairPattern.mainFile.findFirstMatchIn(mainFile)
//        maybeMatch match {
//          case None =>
//            // this shouldn't happen, since we already matched it above
//            PairMatch(mainFile, None, None)
//          case Some(matchResult) =>
//            // extract the base type and id. Use Option to handle nulls.
//            val baseType = Option(matchResult.group("basetype"))
//            val id = Option(matchResult.group("id"))
//            // build the base name and paired regex
//            val baseName = baseType.getOrElse("") + id.getOrElse("")
//            val pairedRegex = pairPattern.pairedFile(baseName)
//            // search in all remaining files for a match on the pairedRegex
//            val maybePair = remainingFileList.find(file => pairedRegex.matches(file))
//
//            maybePair match {
//              case Some(pairedFile) =>
//                PairMatch(mainFile, Option(pairedFile), baseType.map(_.toLowerCase + id.getOrElse("")))
//              case None => PairMatch(mainFile, None, baseType.map(_.toLowerCase + id.getOrElse("")))
//            }
//        }
//    }
//  }

}
