package org.broadinstitute.dsde.firecloud.utils
import akka.event.Logging

/**
 * Created with IntelliJ IDEA.
 * User: hussein
 * Date: 07/23/2015
 * Time: 14:01
 */

case class TSVLoadFile(
  firstColumnHeader:String, //The first header column, used to determine the type of entities being imported
  headers:Seq[String], //All the headers
  tsvData:Seq[Seq[String]] //List of rows of the TSV, broken out into fields
)

object TSVParser {
  private def parseLine( tsvLine: String, tsvLineNo: Int, nCols: Int ) = {
    val fields = tsvLine.split("\t", -1) //note we don't trim the line here in case the last element is deliberately empty
    if( fields.length != nCols ) {
      throw new RuntimeException("TSV parsing error in line " + tsvLineNo.toString + ": wrong number of fields")
    }
    fields.toList
  }

  def parse( tsvString : String ) : TSVLoadFile = {
    val tsvIt = scala.io.Source.fromString(tsvString.replaceAll("\n+$","")).getLines()
     if (!tsvIt.hasNext) {
       throw new RuntimeException("TSV parsing error: no header")
     }

    //Is the header too few or is the problem with the data line?
    val headers = tsvIt.next().split("\t", -1)
    val nCols = headers.length
    val tsvData = tsvIt.zipWithIndex.map { case (line, idx) => parseLine( line, idx, nCols ) }
    TSVLoadFile(headers.head, headers.toList, tsvData.toList)
  }
}
