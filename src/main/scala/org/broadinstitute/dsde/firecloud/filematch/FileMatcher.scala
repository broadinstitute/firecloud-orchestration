package org.broadinstitute.dsde.firecloud.filematch

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.firecloud.filematch.result.{
  FailedMatchResult,
  FileMatchResult,
  PartialMatchResult,
  SuccessfulMatchResult
}
import org.broadinstitute.dsde.firecloud.filematch.strategy.{
  FileRecognitionStrategy,
  IlluminaPairedEndStrategy,
  OntSingleReadStrategy
}

import java.nio.file.Path

/**
  * Given a list of files, pair those files based on their naming conventions.
  * At the time of writing, this involves recognizing Illumina single end and paired end read patterns
  * such as those defined at https://support.illumina.com/help/BaseSpace_Sequence_Hub_OLH_009008_2/Source/Informatics/BS/NamingConvention_FASTQ-files-swBS.htm
  *
  * In the future, we may support additional naming conventions
  */
class FileMatcher extends LazyLogging {

  // the list of recognition strategies to use
  private val matchingStrategies: List[FileRecognitionStrategy] =
    List(new IlluminaPairedEndStrategy(), new OntSingleReadStrategy())

  /**
    * Given a list of files, pair up those files according to our known recognition strategies.
    * @param pathList the list of files to inspect
    * @return pairing results
    */
  def pairPaths(pathList: List[Path]): List[FileMatchResult] =
    performPairing(pathList)

  /**
    * Given a list of files, pair up those files according to our known recognition strategies.
    * @param fileList the list of files to inspect, as Strings
    * @return pairing results
    */
  def pairFiles(fileList: List[String]): List[FileMatchResult] = {
    // convert fileList to pathList
    val pathList = fileList.map(file => new java.io.File(file).toPath)
    pairPaths(pathList)
  }

  /**
    * Implementation for file pairing. This executes in three steps:
    *   1. Use our known file recognition strategies to identify all "read 1" files in the file list
    *   2. Search for all "read 2" files in the file list which match the previously-identified "read 1"s
    *   3. Handle the remaining files which are not recognized as either "read 1" or "read 2"
    *
    * @param pathList the list of files to inspect
    * @return pairing results
    */
  private def performPairing(pathList: List[Path]): List[FileMatchResult] = {
    // find every path in the incoming pathList that is recognized by one of our known patterns
    val matches = findFirstFiles(pathList)
    val successfulMatches: List[SuccessfulMatchResult] = matches.collect { case x: SuccessfulMatchResult => x }
    val partialMatches: List[PartialMatchResult] = matches.collect { case x: PartialMatchResult => x }

    // remove the recognized firstFiles and partialMatches from the outstanding pathList
    val remainingPaths: List[Path] =
      pathList.diff(successfulMatches.map(_.firstFile) ++ partialMatches.map(_.firstFile))

    // process the recognized "read 1" files, and look for their desired pairings in the outstanding pathList.
    // this will result in either SuccessfulMatchResult when the desired pairing is found, or PartialMatchResult
    // when the desired pairing is not found
    val pairingResults: List[FileMatchResult] =
      findSecondFiles(remainingPaths, successfulMatches.collect { case s: SuccessfulMatchResult => s })

    // remove the recognized "read 2" files from the outstanding pathList
    val unrecognizedPaths: List[Path] = remainingPaths diff pairingResults.collect { case s: SuccessfulMatchResult =>
      s.secondFile
    }
    // translate the unrecognized paths into a FileMatchResult
    val unrecognizedResults: List[FailedMatchResult] = unrecognizedPaths.map(FailedMatchResult(_))

    // return results, sorted by firstFile
    (pairingResults ++ partialMatches ++ unrecognizedResults).sortBy(_.firstFile)
  }

  /**
    * find every path in the incoming pathList that is recognized as a "read 1" by our known patterns
    * @param pathList the list of files to inspect
    * @return pairing results
    */
  private def findFirstFiles(pathList: List[Path]): List[FileMatchResult] =
    pathList.collect { path =>
      tryPairingStrategies(path) match {
        case success: SuccessfulMatchResult => success
        case partial: PartialMatchResult    => partial
      }
    }

  /**
    * find every path in the incoming pathList that is recognized as a "read 2" by our known patterns
    *
    * @param pathList the list of files to inspect
    * @param desiredPairings the "read 2" files to look for in the pathList
    * @return pairing results
    */
  private def findSecondFiles(pathList: List[Path],
                              desiredPairings: List[SuccessfulMatchResult]
  ): List[FileMatchResult] =
    desiredPairings.map { desiredPairing =>
      // search for the desired pairing's secondFile in the list of actual files
      pathList.find(p => p.equals(desiredPairing.secondFile)) match {
        case Some(_) => desiredPairing
        case None    => desiredPairing.toPartial
      }
    }

  /**
    * Attempt all the configured file recognition strategies against the supplied file.
    *
    * @param file the file to try to recognize
    * @return SuccessfulMatchResult if the file is recognized; FailedMatchResult if not
    */
  private def tryPairingStrategies(file: Path): FileMatchResult = {
    // does the current file hit on any of our file-matching patterns?
    // Iterate over the matching strategies and return the first successful match result.
    val strategyHit = matchingStrategies.collectFirst(strategy =>
      strategy.matchFirstFile(file) match {
        case success: SuccessfulMatchResult => success
        case partial: PartialMatchResult    => partial
      }
    )
    strategyHit match {
      // The current file is recognized by one of our recognition strategies
      case Some(desiredResult: FileMatchResult) => desiredResult
      // the current file is not recognized
      case _ => FailedMatchResult(file)
    }
  }

}
